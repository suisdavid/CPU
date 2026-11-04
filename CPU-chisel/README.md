# CPU-chisel：可运行的 RV32IM 乱序核心

本次修复基于 `56ceb004d3fec975d5c33a937f12bef32b6453da`（`ugly first draft`）。原稿的模块接口、状态机和依赖处理尚未形成可工作的闭环，因此这次不仅修改语法，还重构了这些部分。保留 **多发射、保留站乱序执行、ROB 按序提交、共享乘除法器、分银行 I-cache 和 SRAM 数据存储**。

## 构建与测试

需要 Java 17、Scala CLI、Python 3.10+、GNU Make、G++ 和 Verilator。Chisel 固定为 **6.4.0**，Scala **2.13.12**，chiseltest **6.0.0**；版本统一声明在 `project.scala`。首次生成 RTL 需要下载 Maven 依赖和 firtool 1.62.0。

在仓库根目录运行：

```sh
# 若 SSH 未配置，先为本地子模块设置 HTTPS URL（不修改 .gitmodules）
git submodule init
git config submodule.testcases.url https://github.com/ACMClassCourse-2025/RISC-V-CPU-2026-Testcases.git
git submodule update testcases

make chisel-check                 # Scala 编译、乘除法与乱序调度单元测试
make rtl                          # Scala -> verilog/generated/student_top.sv
make APPIMAGE=                    # 编译提交用 ./code，原有 OJ 入口不变
make test APPIMAGE= MAX_CYCLES=100000000
make perf APPIMAGE= MAX_CYCLES=100000000
python3 tests/regression.py --sim build/sim
make stress-test VERILATOR=verilator
```

如果使用课程 AppImage，保留原来的 `APPIMAGE` 配置；`stress-test` 需要能直接调用的原生 Verilator。`SCALA_CLI=/path/to/scala-cli` 和 `VERILATOR=/path/to/verilator` 可覆盖工具位置。

**修改 Scala 后必须先 `make rtl` 再构建仿真器。** 生成的 `student_top.sv` 随提交保存。OJ 执行原来的 `make` 时只编译 RTL，不依赖 Java、Scala、网络或 Maven。辅助测试不会改变官方仿真器或标准答案。

**周期上限说明：** 框架默认 `MAX_CYCLES=1000000`，不足以完成所有较大的程序，例如 `correctness_pi` 有 3,117,658 条动态指令。这一版每周期最多退休一条；完整功能回归使用 100,000,000 周期上限。默认一百万周期下的超时不能算通过，也不能据此声称已满足线上 OJ 的周期/性能限制。

## 数据通路与修复内容

| 模块 | 当前职责与关键行为 |
|---|---|
| `Decoder` | 组合译码；分开寄存器编号与立即数；I/S/B/J 立即数正确重排并符号扩展；保留非法指令标记，在退休时断言 |
| `Frontend` | `issueWidth` 个 SRAM I-cache 银行；一次收集连续指令包；可部分消费；回滚丢弃旧指令包并排空未完成请求 |
| `Issuer` | 按序分配 ROB/RS；ROB 槽位作为生产者标签；同组 RAW/WAW、x0、ROB/CDB/退休旁路 |
| `Executer` | 共享保留站扫描所有就绪项，最多 `ALU_NUM` 条普通运算并行执行；一个流水乘法器、一个 32 步迭代除法器 |
| `Committer` | 显式占用计数，正确区分满/空和指针回绕；按序退休；结果回写与访存完成分开记录 |
| `CPU` | 维护已提交寄存器值与重命名映射；地址计算可乱序，外部 Load/Store 仅在 ROB 队首执行；分支退休时纠正预测 |
| `Cache` | 每行 4B，参数化组数/路数；数据使用课程同步 SRAM，tag/valid/替换指针使用寄存器；读缺失分配，写直达内存、命中时更新、未命中不分配；MMIO 绕过缓存 |
| `AxiBridge` | 锁存请求与归属；AR、AW、W 各自维持 VALID 和 payload 直到握手；AW/W 分别记账；等待 B 响应完成 Store |
| `Predictor` | 退休时训练的二位饱和计数器 BHT；JAL 直接预测目标，JALR 初始预测顺序地址，ROB 检查真实 next PC |

关键正确性修复：

- `ADDI a0, x0, 255` 是普通指令。结束程序只使用向 `0x80000000` 写入完整 32 位结果的课程 MMIO 协议。
- JALR 读取写入 rd **之前**的 rs1，即使 `rd == rs1`；目标最低位清零；返回地址为原 PC+4。
- 乘法先扩展符号再取高位；除法覆盖除零、有符号溢出、向零截断及余数符号，试减使用 33 位余数。
- Load/Store 对总线发出 4B 对齐地址，按低两位移位数据/字节掩码；LB/LH 正确符号扩展。
- 分支只在退休时进行全量恢复，此时所有更老指令已提交；清空年轻 ROB/RS/重命名以及乘除法状态。年轻 Store 无法提前写内存。
- 回滚不撤销已经发送的 AXI 请求。取指包等待旧响应收齐后丢弃，避免响应错配。
- 停顿时保留前端数据，不能像原稿一样在缓冲区满时丢弃输入。

## 参数

通过 JVM 系统属性在**生成 RTL 时**配置（运行仿真器时修改这些属性不会改变已有 RTL）：

| 属性 | 默认值 | 约束 |
|---|---:|---|
| `cpu.issueWidth` | 4 | 1、2、4、8 |
| `cpu.robDepth` | 16 | 不小于发射宽度，至少 2，2 的幂 |
| `cpu.rsDepth` | 16 | 不小于发射宽度 |
| `cpu.aluCount` | 4 | 1 到 RS 深度 |
| `cpu.cacheSets` | 128 | 至少 2，2 的幂 |
| `cpu.cacheWays` | 4 | 正整数 |

例如生成另一套 RTL，避免覆盖提交版本：

```sh
scala-cli run CPU-chisel --server=false -M cpu2026.Generate \
  --java-opt -Dcpu.issueWidth=2 --java-opt -Dcpu.robDepth=8 \
  --java-opt -Dcpu.rsDepth=12 --java-opt -Dcpu.aluCount=2 \
  --java-opt -Dcpu.cacheSets=16 --java-opt -Dcpu.cacheWays=3 \
  -- build-config2/rtl
make build APPIMAGE= FILELIST=build-config2/rtl/filelist.f BUILD=build-config2
python3 tests/regression.py --sim build-config2/sim --seeds 3
```

默认 I-cache 数据容量为 `4 × 128 × 4 × 4B = 8KiB`，D-cache 为 `128 × 4 × 4B = 2KiB`。

## 当前边界

这版是可验证的功能基础，尚未做面积/时序优化。相比原稿计划，明确采用了以下较保守的实现：

- 单条退休；访存按序，无 Store-to-Load forwarding；单个 AXI 未完成事务。
- D-cache 使用 write-through / no-write-allocate，不实现 dirty eviction/write-back；替换采用轮转指针，不是 LRU。
- 使用简单 BHT，暂未实现原稿设想的 local/global 混合预测器。
- 使用 ROB 值槽重命名，没有独立可配置 PRF；若课程要求单独的 PRF 参数，需要后续扩展。
- 不支持异常处理、非对齐访存、CSR、ECALL/EBREAK、FENCE.I 或自修改代码，这些不在当前课程正确性范围内。
- 测试通过不能代替隐藏测试、综合面积或时序验证；实际测试结果见 `VALIDATION.md`。
