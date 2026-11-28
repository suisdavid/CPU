1. 四路提交的收益说明，下一步应优先检查指令供给

同样在 LATENCY=10 下：
测试	四路提交后的 IPC
median	0.2100
multiply	0.5994
qsort	0.3223
rsort	0.2609
towers	0.4404
vvadd	0.1645
几何平均	0.3025
提交宽度从 1 增加到 4，几何平均 IPC 从 0.2969 → 0.3025，仅提高 1.89%。这说明在这些测试中，单路提交并不是普遍占主导的瓶颈。[测试记录](https://github.com/suisdavid/CPU/blob/dc24d15e09eba5fffde4ca9e524d3ba39f0fb7c9/CPU-chisel/measurements/ipc-issuewidth.json)

目前没有停顿分类数据，因此不能严谨地说“访存占了 70%”。但下面几处结构限制可以直接从代码确认。

2. 最值得先改：预测不跳转的分支，也会中断取指

在 [Issuer.scala](https://github.com/suisdavid/CPU/blob/dc24d15e09eba5fffde4ca9e524d3ba39f0fb7c9/CPU-chisel/Issuer.scala#L325) 中，条件分支算完预测的下一 PC 后，无条件执行：
```yaml
halt := 2.U
```
而 halt === 2.U 会：
- 停止本周期后续指令的分发；
- 设置 out_pc_val_changed；
- 设置 frozen；
- 让 CPU 取消当前取指事务，并清除 I-cache 的请求状态。
因此，即使预测是 not taken，下一条就是 PC+4，也会触发这些操作。已有缓冲有时能减轻影响，但不必要的重定向仍然存在。

这是一个很适合先做的局部优化。候选改法是：
```yaml
// 条件分支：预测不跳转时，允许继续沿顺序路径分发。
halt := Mux(rs.dest =/= 0.S, 2.U, 0.U)
```
这是修改方向，尚未作为补丁验证；分支仍需正常进入 ROB，预测错误仍走原来的恢复逻辑。

**Trade-off：**几乎不增加存储面积，改动较小；但可能让更多 lane 的分发逻辑在同周期生效，需要检查时序，并测试同组多分支、组内依赖和预测错误恢复。

另外，预测 taken 的分支目前也要到 Issuer 阶段才改变取指方向。所以预测正确依然有气泡。后续可以通过前端 BTB 改善，单纯扩大预测表解决不了这一部分。

3. 访存通路：四个 I-cache bank，实际上串行等待内存

[CPU.scala](https://github.com/suisdavid/CPU/blob/dc24d15e09eba5fffde4ca9e524d3ba39f0fb7c9/CPU-chisel/CPU.scala#L305) 的读通路只有一个 read_owner，状态是：
选择请求 → 发送 AR → 等待 R → 才能选择下一个请求。
因此，所有 I-cache bank 和 D-cache 共用一个 outstanding read 名额。

若一次取指四个 bank 都 miss，它们需要依次等待返回；与此同时，取指组必须等四条全部到齐才能进入 decoder。

简化地说，假设每次内存访问延迟为 \(L\)，忽略握手和其他开销：
- 当前四次串行访问：约 \(4L\)；
- 能连续发送、顺序返回：约 \(L+3\)。
这只是说明可重叠的延迟，不是对最终 IPC 提升的预测。当前[仿真内存](https://github.com/suisdavid/CPU/blob/dc24d15e09eba5fffde4ca9e524d3ba39f0fb7c9/scripts/sim.cpp#L76)确实使用请求/响应队列，能够接收连续读请求，因此 CPU 有可利用的并发空间。

还有一个额外限制：
```scss
when(!fetch_active && ... && fetcher_waiting === 0.U && ...)
```
D-cache 的读 miss 尚未返回时，连新一组 I-cache 查询都不能开始，即使它们本来可能全部命中。

具体可以分两步改：
修改	作用	Trade-off
允许 D-cache miss 期间启动 I-cache 查询	重叠取指命中与数据访存等待	改动较小；必须保持返回归属与重定向丢弃逻辑正确
将单个 read_owner 改成 2～4 项响应归属 FIFO	允许不同 bank 的 miss 重叠	增加少量队列和控制；需要防止重复发送同一个 miss，并处理背压、旧路径返回
第一版可以保留每个 cache 最多一个 miss，只让不同 cache/bank 之间并发。这样比较符合你希望逐步修改原架构的要求。

4. LSU 和 store 提交也在限制并行度

在 [Executer.scala 的 LSU](https://github.com/suisdavid/CPU/blob/dc24d15e09eba5fffde4ca9e524d3ba39f0fb7c9/CPU-chisel/Executer.scala#L628) 中，load 发出后设置 in_process := true.B，直到数据返回才清除。公共调度逻辑在此期间不选择下一条指令。

因此：
- 一条 load 等内存时，另一条独立 load 无法继续发出；
- 后面的 store 地址/数据执行也被这次等待挡住；
- 仅增加 ROB 或 ALU 数量，无法解除这个限制。
此外，[Committer](https://github.com/suisdavid/CPU/blob/dc24d15e09eba5fffde4ca9e524d3ba39f0fb7c9/CPU-chisel/Committer.scala#L133) 遇到 store，必须等 cache 完成确认才能退休，且 store 会结束本周期的提交组。这里普通 cache-hit store 等的是 D-cache 完成，并不是每条 store 都要等外部 AXI 写响应。

两个修改方向：
方案	具体实现	收益与代价
4 项 committed store buffer	store 在 ROB 头部时写入缓冲即可退休，后台按序写 D-cache	能减少 store 阻塞提交；增加地址比较、按字节转发和缓冲管理
LSU 支持 2～4 个待返回 load	独立保存每个请求的 ROB 标识、目标寄存器、访问宽度和转发信息	可重叠独立 load；必须配合 D-cache 的多个 miss 记录，否则 miss 仍然串行
Store buffer 必须让后续 load 能读到尚未写入 cache 的旧 store，并让退出 MMIO 等待必要的旧写入完成。多 load 则不能直接删除 in_process 判断：当前只有一份 cur_rs，会丢失返回结果对应的指令信息。

vvadd 的主循环实际包含两条 load、一条 store 和一条回跳；其低 IPC 与这些限制吻合。但各项究竟贡献多少，仍需要计数器验证。

5. Cache 命中吞吐、缓存行和 ROB，也有进一步优化空间

[Cache.scala](https://github.com/suisdavid/CPU/blob/dc24d15e09eba5fffde4ca9e524d3ba39f0fb7c9/CPU-chisel/Cache.scala#L85) 当前在处理上一请求时拉低 out_ready。即使连续命中，也不能每周期接收一个请求。

结合 fetch_active 的控制，当前无阻塞、连续命中的顺序取指最多约为：
\[ \frac{4\text{ 条}}{2\text{ 周期}}=2\text{ 条/周期} \]
这个上限本身不足以解释 0.3 IPC，但它说明前端尚不能持续供给四路后端。
修改选项	适用情况	Trade-off
Cache 查询流水化＋取指缓冲	命中时仍有大量前端空泡	可提高命中吞吐；需处理 SRAM 读写冲突、refill 和背压
只等待按 PC 顺序已返回的连续前缀	前几条已到，后面的 bank miss	能提前译码执行；需要保存剩余指令与 PC，防止重复发送
16B 缓存行或相邻字预取	连续数组访问、顺序取指	利用空间局部性；增加填充、逐字有效位、写回控制，也可能污染 cache
ROB 从 16 增至 32 项	经常 ROB 满且存在可执行的年轻指令	扩大隐藏延迟的窗口；增加比较、选择逻辑与时序压力
这里有两个容易踩坑的地方：
- **4B 总线不要求缓存行只能是 4B。**但 16B 行仍需四次总线读；如果继续串行填充，还必须整行等完，关键字的等待反而可能变长。应结合流水读、关键字优先返回考虑。
- 当前 ROB 实际可用 15 项，而且指针和 ringIndex 多处写死为 4 位。不能只把 ROBWidth=16 改成 32。
6. 分支提前恢复和乘除法解耦，适合作为后续方案

当前分支执行单元发现预测错误后，仍要经过 CDB、ROB，等分支到达提交位置才恢复。前面若有长延迟 load，错误路径可能继续占用资源。

可以考虑：
- 先加小型 BTB，例如 64 项，配合 8 项 RAS：尽早预测 taken 分支、跳转和返回的目标。代价是预测元数据、取指组截断与恢复状态。
- 再考虑执行阶段恢复：缩短 mispredict penalty，但需要保留更老指令、撤销年轻指令并恢复重命名状态。不能直接把 BRU 的结果接到现有“清空整个 ROB”的 flush 上。
乘除法方面，普通 ALU 运算没有被 in_process 强制变成多周期；但乘除法会占住发起它的 ALU，等待期间该 ALU 不能处理其他指令。可用独立 MDU 请求队列和结果标识解耦，代价是额外队列及写回仲裁。它是否值得优先改，要看乘除法阻塞计数。

我建议的实际修改顺序
顺序	修改	理由
第一步	消除预测 not-taken 分支的不必要 redirect	改动最局部，浪费明确
第二步	允许 I-cache 查询与 D-cache miss 重叠	去掉前后端之间不必要的等待
第三步	AXI 读通路增加 4 项归属 FIFO	先利用现有四个 I-cache bank 的并行性
第四步	根据计数器选择 store buffer 或多 load 支持	分别针对提交被 store 卡住、独立 load 无法重叠
第五步	前端 BTB、Cache 流水化、ROB 扩容	在前面瓶颈缓解后继续提高吞吐
每一步单独测六项性能测试，并保持 LATENCY=10；正确性继续覆盖不含 pi 的测试和 AXI 背压测试。建议同时记录 每周期提交 0～4 条的分布、ROB 满/空、I/D-cache miss、读通路忙周期、分支重定向次数、LSU 等待周期。

**现阶段我最推荐先做前三步，保留你的 ROB、RS、ALU 和四路 Committer 架构。**在这些串行等待解除前，继续增加 ALU、发射宽度或预测表大小，收益很可能有限。
1 个已选文本片段
rs.dest是否为0分别是什么含义来着
这里是在条件分支指令的处理逻辑中，`rs.dest` 存的是预测方向，来自：
```yaml
rs.dest := predictor_buffer(...)(0).asUInt.zext
```
rs.dest	含义	预测的下一条地址
0	预测不跳转（not taken）	PC + 4
1	预测跳转（taken）	PC + 分支偏移量
所以：
```yaml
halt := Mux(rs.dest =/= 0.S, 2.U, 0.U)
```
表示：
- 预测跳转：`halt = 2`，停止本组后续分发，通知前端重定向。
- 预测不跳转：`halt = 0`，继续沿顺序路径分发。
注意，这是 rs.dest 在条件分支中的特殊用途；普通算术指令中，它存的是目标寄存器编号。
rob.dest:=Mux(rs.dest=/=0.S,id_buffer(ringIndex(startingPoint,i.U))+4.U,(id_buffer(ringIndex(startingPoint,i.U)).asSInt+rd(12, 0).asSInt).asUInt).asSInt
                                那这是为什么，不应该不跳转才到id_buffer(ringIndex(startingPoint,i.U))+4.U吗
因为这里 rob.dest 保存的是“预测错误时的恢复地址”，所以条件恰好反过来。

设当前分支地址为 PC，跳转目标为 target：
预测方向 rs.dest	预测执行路径	预测错误时应恢复到 rob.dest
0：预测不跳转	PC + 4	target
1：预测跳转	target	PC + 4
因此这行等价于：
```yaml
rob.dest := Mux(predictedTaken, PC + 4.U, target).asSInt
```
正常预测的下一 PC 是由另一行设置的：
```scss
out_pc_val(i+1) := Mux(
  rs.dest =/= 0.S,
  /* PC + 分支偏移 */,
  out_pc_val(i) + 4.U
)
```
后面 BRU 用“实际跳转方向 XOR 预测方向”判断是否预测错误。Committer 只有发现预测错误时，才通过：
```yaml
io.out_newaddr := rb.dest.asUInt
```
恢复执行。

例如：预测跳到 0x120，于是提前把 rob.dest 存成 0x104。如果后来发现实际不跳转，就恢复到 0x104；预测正确时，这个恢复地址不会使用。