# 原稿修改对照

基线：`56ceb004d3fec975d5c33a937f12bef32b6453da`。完整逐行差异见 PR 的 Files changed；下表解释各文件修改的原因。

| 原文件 | 原稿的问题 | 修改及保留的结构 |
| --- | --- | --- |
| `Decoder.scala` | 输出声明为 Input；I/load/JALR 只取了 5 位立即数；JAL 缺最低位；未译码字段没有默认值 | 修正方向、位段和默认值，保持原 switch 译码与一级寄存器；增加回滚清除 |
| `Register.scala`、`CDB.scala` | Register 类名写成 RS；广播没有有效标志 | 修正类名，增加 CDB.valid，避免无结果时的旧值重复广播 |
| `Issuer.scala` | 环形缓冲区可用/占用量混用；重命名数组动态索引跨发射级；遗漏当前广播/提交旁路；JALR rd=rs1 使用了新值 | 保留 op/id 缓冲区和逐发射槽的 prods/spaces 传播；限定每级读取范围；修正 x0、旁路、目标地址和停止发射条件；预测信息随指令入缓冲区 |
| `Executer.scala` / Executer | RS 只分配 8 项而配置为 16；非队首出队时移动源索引错误；唤醒结果可能被移动覆盖 | 按配置分配；先形成 woken，再按原顺序选第一条可执行指令，并移动到新的环形位置；仍支持年轻就绪指令先执行 |
| `Executer.scala` / Mult | 部分积命名错误，遗漏低半部加法进位，高/低输出选择反了 | 保留四个 16×16 部分积、四个加法器和两级流水；补进位及低半部非零标志，用于有符号高半部结果修正 |
| `Executer.scala` / Divider、ALU | 用减法结果符号判断无符号大小；移位余数丢最高位；有符号除零及乘法高半部修正错误；立即移位未限 5 位 | 保留 32 级除法与共享资源握手；试减余数用 33 位，按无符号比较；补零除/溢出/符号边界与请求默认值 |
| `Executer.scala` / LSU | 老 store 地址未就绪时可能提前 load；部分转发值未与返回内存数据合并；初次请求的立即应答可能丢失 | 保留从 ROB 顺序搜索并按字节覆盖的转发方式；等待未知老 store；锁存转发字节并合并内存返回；修正请求握手 |
| `Committer.scala` | ROB 重复声明；store 在写完成前离开 ROB；无效 CDB 也可能匹配；输出缺默认值 | 初始修复保留按序单条退休；2026-11-25 按用户要求扩为最多 issueWidth 条按序退休及多路回写。store 到完成应答才出队，作为提交组边界；保留单个预测器更新端口；使用 valid 广播并清除已退休的 ready |
| `Predictor.scala` | 端口名、布尔/数值类型错误；更新历史及保存的预测字段不匹配 | 保留 LHT、PHT、Gshare、chooser 和原配置；训练保存的查询索引，推进当前已提交历史 |
| `Cache.scala` | 同步 SRAM 查询时序不完整；未检查 valid；数据寄存器只有 4 位；部分写缺失丢失其他字节；可能写回未选中的脏路 | 保留原 lookup/refill/writeback 三状态、四路 tag/data SRAM、LRU 与 write-back；补 ready/done、部分写合并、选定 victim 的地址/数据锁存和回写应答 |
| `CPU.scala`、`StudentTop.scala` | LSU/BRU 实例类型错误，缺连线；取指未保留各 bank 返回；AW/W 被当成同时握手；回滚撤销未结束事务；提交把 valid 当寄存器索引 | 保留原模块连接、发射分流和乘除仲裁；保留四个 I-cache bank；增加请求所有者/载荷及独立握手位；提交完整 32 位 MMIO 退出结果；回滚排空旧事务 |
| `Config.scala` / ringIndex | FIRRTL 中的 4 位环形加法内联成 packed Vec 下标后，Verilator 5.020 将表达式扩宽；15+1 读到越界槽位 | 显式保留 4 位索引连线，不增加状态。由整机波形定位；修复后原失败的部分字节转发与 hanoi 均通过 |
| `Generate.scala`、`checks`、`tests` | 没有完整 CPU 生成入口及覆盖关键边界的独立检查 | 增加生成入口、算术/RS 单元测试、自检 RV32IM 程序、独立随机 AXI 从机；课程仿真器和测试判定代码保持原样 |

配置保持四发射、四 ALU、16 项 ROB/RS/发射缓冲区（环形队列留一空槽）、四路 128-set Cache。现有索引宽度与此配置绑定，尚未声称其他配置已验证。

生成时保留 Vec/Bundle，Cache 每路每种元数据只保留一个索引写入口，RS/ROB 的广播更新按物理槽进行。这些改动减少生成器展开的重复连接，保留队列顺序、存储容量和替换策略。

## 2026-11-25：多提交

`Committer.scala` 从 ROB 队首扫描最多 `issueWidth` 个条目，只退休连续就绪前缀，以实际退休数量推进队首。未就绪、halt、误预测分支、第二个分支或 store 边界会停止扫描；store 应答前保持载荷，且仍可向年轻 load 转发。当前配置每周期最多退休 4 条普通指令，最多训练 1 个分支；store 提交后结束本周期提交组。

`CPU.scala` 将寄存器回写及送给 Issuer 的退休旁路扩为 `issueWidth` 路。相同目标寄存器由更年轻的提交槽覆盖；只有退休生产者匹配当前 producer 且本周期没有新的重命名时才清除 producer。预测器、Cache、执行单元及 AXI 数据路径未修改。

`checks/CommitterTests.scala` 覆盖满宽退休、同时入队/出队、槽 15 跨环回绕、CDB 就绪缺口、store 应答与背压、分支端口顺序、每个槽位的误预测截断、halt 与 x0。整机定向测试增加在长延迟 DIV 后积累 WAW/RAW 指令的场景。
