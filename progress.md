# CPU 交接进度

更新日期：2026-11-28。当前分支：`main`。本次基于 `7d494615cfd7e538ffdc5a8c0d5ab2c42a0be00f` 修改。

## 2026-11-28：修复 fix.md 第 3 项

用户已完成第 2 项；本次保留该修改，直接修改 Chisel 并提交 `main`。按要求不运行性能、面积或时序测试。

### 实现

- `CPU.scala` 移除新一组 I-cache 查询对共享读通路空闲的要求：D-cache miss 等待期间可以查 I-cache，命中指令可以继续进入 decoder。
- 新增 `ReadArbiter.scala`：4 项响应归属 FIFO + 一个预留 FIFO 名额的 AR 保持寄存器。计入 AR 预留后总共最多 4 笔，不是 4+1 笔；没有背压时可连续发送 AR。
- 每个 I-cache bank/D-cache 一个 busy 位，从选择请求起直到 R 握手保持，防止 `out_neednew` 持续为高导致重复读。D-cache 优先，再按 I-cache bank 编号仲裁。
- `ARVALID/ARADDR` 在背压和重定向期间保持；重定向不会清空已接受事务。旧 I-cache 的 FIFO 项及尚未握手的 AR 都标记丢弃，返回后释放 busy。新路径可以查询同一 bank，但若 miss，需等待该 bank 的旧事务排空。
- D-cache 返回继续完成 cache 内部工作，错误路径 load 的结果仍由现有 `lsu_discard` 屏蔽；dirty writeback、store、AW/W/B 通路保持原行为。
- 单个 cache 仍只有一个 miss，取指仍按完整四条交给 decoder。后续 LSU/store buffer、Cache 流水化等工作写入 `fix.md`。
- 新增 `ReadArbiterTests.scala`，覆盖四笔未返回读、满队列、持久 miss 去重、D-cache 优先、AR/R 同周期、AR 背压、重定向与旧返回丢弃、连续重定向和环绕。
- `tests/axi_stress.cpp` 改为按序响应 FIFO，默认允许 4 个 outstanding；`AXI_READ_DEPTH=1` 可检查只接受单请求的从机。R 数据在接收请求时保存，响应期间保持稳定；增加读并发/I-D 重叠的覆盖统计。

### 验证与生成文件

- Scala 2.13.12 / Chisel 6.4.0 编译通过；firtool 1.62.0 完成 RTL 生成；Verilator 5.032 构建普通和背压仿真器通过。
- `ReadArbiterTests` 通过：1,345 笔有序响应，其中 363 笔旧路径响应丢弃、578 次 AR/R 同周期；验证队列上限为 4，以及背压、连续重定向、去重和环绕。
- 普通内存回归：21/21 程序、6,574 项寄存器/内存自检通过。
- 默认 4 项队列的随机 AXI 背压回归：21/21 程序、6,574 项自检通过。
- `AXI_READ_DEPTH=1` 单请求从机：定向程序 1,554 项自检通过，确认持续 AR 背压下兼容串行从机。
- 额外以 AXI 随机种子 `20261128` 运行定向程序：返回值正确，最大未返回读请求为 4；观察到 43 次 D-cache 读尚未返回时接受指令读，确认整机连线确实允许重叠。
- 已更新 `verilog/generated/student_top.sv`，SHA-256：`fa34df47e5cf49ff3a7ea8ed1ec91639eaea55268b4588091552abf6cbf22bca`；生成文件与本次 Chisel 同步。
- `git diff --check` 通过。未重新运行官方 correctness 测试集，未运行官方性能集、IPC 对比、面积综合或时序测试。

### 本地复现（仓库根目录）

```bash
git switch main
git pull --ff-only origin main
scala-cli run CPU-chisel --server=false --jvm system -M cpu2026.ReadArbiterTests
scala-cli run CPU-chisel --server=false --jvm system -M cpu2026.Generate
make build APPIMAGE=
python3 tests/regression.py --sim build/sim --seeds 20
python3 tests/build_stress.py
python3 tests/regression.py --sim build-stress/sim --seeds 20 --stress
AXI_READ_DEPTH=1 python3 tests/regression.py --sim build-stress/sim --seeds 0 --stress
```

以下为历史交接记录；其中 IPC、官方正确性、RTL 摘要和面积状态均属于旧版本，不代表本次验证。

## 2026-11-26 历史交接

更新日期：2026-11-26。分支：`codex/repair-original-chisel`，PR：<https://github.com/suisdavid/CPU/pull/2>。

用户最新要求：停止继续面积测试，提交全部最终代码、测试脚本和进度，并提供本地 IPC/综合命令。此交接没有启动新的面积任务。上轮执行环境已重置，旧并行任务会话不存在；本次从远端恢复最新源码及会话中尚未提交的脚本。

## 已完成

- 在原代码中修复编译及功能问题，保留原有四发射、RS/ROB、算术单元、预测器和缓存结构。
- 按后续要求只在 `CPU-chisel/Committer.scala` 和 `CPU-chisel/CPU.scala` 改动多提交硬件：每周期最多 `issueWidth=4` 条按序退休，多路回写和提交旁路；同目标寄存器年轻值优先，新重命名不被旧提交清除。
- 保留单 store 事务和单预测器更新端口：store 等待 ACK 且结束提交组；第二个分支等待下周期；误预测、halt、未就绪缺口阻止年轻副作用。
- 生成 RTL 已入库：`verilog/generated/student_top.sv`，SHA-256 `f3843a649f09385b383eb00ea9c63e384618abd9ecba186f049e70a614eb16a1`。硬件提交 `e79d60ae8bbe129d1c078035a72820aa4fc856d0`。
- Scala/Chisel/firtool、Verilator 构建及 Committer 定向单元测试通过。覆盖满宽退休、同时入队、40 轮环绕、槽 15 跨环、CDB 缺口、store 背压、分支排序、各槽误预测、halt、x0、clear。
- 普通 AXI 和独立随机阻塞 AXI 各通过 21 个程序、6,574 项检查。
- 排除 `pi` 后官方正确性 18/18 通过，条件为 `LATENCY=10`、`MAX_CYCLES=100000000`。`qsort=2629393`、`tak=1884271`，仍超过默认 100 万周期，未验证线上隐藏测试。
- 官方性能 6/6 通过，几何平均 IPC 从 `0.2969101015993677` 到 `0.3025303017987908`，提升约 1.8929%。`vvadd` 小幅回退，其余五项改善。未测停顿计数器。

详细记录：[多提交验证](CPU-chisel/VALIDATION_ISSUEWIDTH.md)、[性能](CPU-chisel/PERFORMANCE.md)、[修复说明](CPU-chisel/REPAIR_NOTES.md)。

## 面积：按要求停止，尚无总面积

- 工具：固定课程 Yosys 0.63 commit `70a11c6bf0e8dd669f56c7da3587f78b405138e2`、对应 ABC、ASAP7 RVT TT、FakeRAM、sv2v 0.0.13。
- `tests/GenerateArea.scala` 从同一硬件源码生成 `--preserve-aggregate=none` RTL，再经 sv2v 转换。该输入此前通过 6 项 perf 和 21 程序/6,574 项回归，与提交 RTL 的输出及周期数完全相同；不是形式等价证明。摘要见 [area-input-issuewidth.json](CPU-chisel/measurements/area-input-issuewidth.json)。
- 默认扁平化流程此前被 SIGKILL 终止，疑似内存不足；`diagnose` 的 `opt_hier` 曾报 Adder 端口不匹配。不能声称默认综合通过。
- 最后可观察到的层次化流程：前六阶段完成、20/20 模块 techmap 完成、全局 dfflibmap 完成；ALU、ALU_1 的串行 ABC 完成。切换三任务并行后，Adder 映射导出完成，ALU_2、ALU_3、BRU 正在运行。此后进度未确认，最终网表合并、全设计 mapped 检查和面积汇总均无已确认结果。
- `tests/parallel_area.py` 保留相同 ABC 命令及库，独立导出所选模块后合并；SHA-256、单模块导出边界、全设计检查和独立面积核对均在脚本中。最终端到端流程未完成，不能当成已验证的面积报告。
- `tests/hierarchical_area.py --abc-limit N` 可以在保存 N 个串行 ABC 模块后停下；`parallel_area.py --abc-prefix N` 从该前缀继续。最新串行前缀为 2；本地从头运行建议前缀 0，命令见下文链接。
- 未运行 OpenSTA；2 ns 为 ABC 目标，不是已验证频率。历史 SRAM 模型面积 5,740.255672 µm² 只代表 SRAM 部分，不能当作 CPU 总面积。

已保存的中间快照：[CPU-area-progress.tar.gz](https://chatgpt.com/library?file_id=libfile_a888220a23648191a2565dd3a06417d2&account_id=personal)，最后确认版本 2，158,950,607 字节。它包含准备输入、stage-05、完整 module-19、dfflibmap 和 **ALU（abc-00）** 检查点及校验链；不包含后来完成的 ALU_1/Adder。module-19 SHA-256 为 `8b2623e353be68e2c91caab21b697d5d34a8dad454f91235ee8c6707b4cc9c51`，abc-00 为 `60157711edfa5f44673caa7fcbf87aa8d27a58071690c85db5514349f95badcd`。检查点签名绑定原路径及工具摘要，跨机器本地运行应按文档重新生成，不强行改签名。

## 本地运行及交接范围

完整、可复制的命令：[docs/LOCAL_TESTING.md](docs/LOCAL_TESTING.md)。包含 Docker 环境准备、Chisel 生成、IPC、排除 pi 的正确性测试、课程默认综合及可续跑层次化流程。

仓库保存源码、生成的提交 RTL、测试脚本、测量数据和文档。工具二进制、依赖缓存、仿真构建目录和大型综合中间网表不作为 Git 源码提交；必要的中间恢复快照见上方链接。

本次交接仅检查 Python 语法/CLI、文档命令与当前脚本参数、生成 RTL 摘要及 Git 差异，不重新运行 CPU 回归或面积综合。新增的 Docker 扩展镜像未重新构建；完整综合和时序仍需在本地执行。

## 2026-11-26 本地 Docker 构建修复

用户日志显示 GCC 13.3 在链接 OpenSTA 时发生 `lto1` / `jobserver_info::return_token` 内部错误。Dockerfile 现对固定 OpenSTA 源码显式禁用 Release IPO/LTO；其上游 CMakeLists.txt 会强制开启该属性，不能只靠 `-D` 参数关闭。CPU、Yosys/ABC、ASAP7 配置不变。完整 Docker 构建仍需用户本机确认，没有重启面积测试。

验证：使用固定上游的真实 IPO 配置片段、CMake 3.28.3 和本机 GCC 14.2 做三目标最小构建。确认原配置覆盖两个命令行 OFF 设置，修复后 OpenSTA/sta_swig/sta 均无 `-flto` 编译参数，sta 链接亦无 `-flto`，最小程序构建及运行通过。验证使用了 Dockerfile 中同一条 sed 命令；没有声称复现了用户的 GCC 13 崩溃或完成了完整 Docker 镜像构建。
