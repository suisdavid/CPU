# 验证记录

验证日期：2026-11-03 至 2026-11-04。

- 修复基线：`56ceb004d3fec975d5c33a937f12bef32b6453da`。
- 官方测试子模块：`29f980727f7d99a1842a58f34091c7579ba3fe85`。
- Linux x86-64，OpenJDK 17，Scala CLI 1.12.3，Scala 2.13.12，Chisel 6.4.0，chiseltest 6.0.0，firtool 1.62.0，Verilator 5.020，G++ 13.3。
- 实际运行的是本次生成的 `student_top.sv`，通过原始课程 C++ 仿真器执行程序；没有使用软件 CPU 代替 RTL，也没有修改标准答案。
- 提交 RTL 的 Git blob：`a9716566db0b5937521b18cc13676eca7fdf2981`。

## 构建和模块验证

| 检查 | 结果 |
|---|---|
| 所有 Scala 源文件编译 | 通过 |
| Chisel 硬件展开及 firtool SystemVerilog 生成 | 通过 |
| 原始课程 Verilator 仿真器构建（启用断言） | 通过 |
| 共享乘法器 | 896 组边界/随机向量通过，包含四种 MUL 和 flush |
| 共享除法器 | 896 组边界/随机向量通过，包含四种 DIV/REM、除零、溢出与取消 |
| 保留站乱序调度 | 较老指令等待操作数时，较年轻就绪指令先执行；随后 CDB 唤醒较老指令，通过 |
| `git diff --check` | 通过 |

工具仍输出非阻断警告：Chisel 对 one-hot 多路选择后的枚举转换给出提示；Verilator 提示生成文件内模块名、未使用的位以及课程 SRAM 模型的阻塞赋值。没有组合环路、锁存器或多驱动错误导致构建失败。

## 官方测试

使用默认 CPU 配置、内存延迟 10、**100,000,000 周期上限**：

```sh
python3 scripts/testcase.py --kind correctness --build build --max-cycles 100000000
python3 scripts/testcase.py --kind perf --build build --max-cycles 100000000
```

正确性：**19 passed, 0 failed**。包括 add_to_100、array_test1/2、basicopt1、bulgarian、expr、gcd、hanoi、lvalue2、magic、manyarguments、multiarray、naive、pi、qsort、queens、statement_test、superloop、tak。

| 性能基准 | 动态指令数 | 周期 | IPC |
|---|---:|---:|---:|
| perf_median | 6,961 | 45,185 | 0.1541 |
| perf_multiply | 21,722 | 56,345 | 0.3855 |
| perf_qsort | 139,900 | 746,255 | 0.1875 |
| perf_rsort | 195,719 | 1,208,886 | 0.1619 |
| perf_towers | 5,278 | 46,154 | 0.1144 |
| perf_vvadd | 4,524 | 32,340 | 0.1399 |
| 几何平均 | | | **0.1751** |

**默认一百万周期的初次测试只有 13/19 通过，6 项超时。** 增大周期上限后这 6 项也得到正确结果。此记录不代表已经满足线上 OJ 的固定周期限制；当前设计每周期最多退休一条、访存保守串行，仍需要性能优化。

## 新增整机回归

`tests/regression.py` 直接编码自检 RV32IM 程序，无需交叉编译器。运算预期值由独立 Python 整数运算生成，程序逐项检查结果；失败时写出检查编号并保存可重放输入。

- 一个定向程序：1,452 项检查，覆盖全部 RV32IM 算术、移位、立即数、x0、AUIPC、分支、JAL/JALR、`rd == rs1`、字节/半字符号扩展、写掩码、缓存冲突、负偏移及 ROB 回绕。还验证 `ADDI a0,x0,255` 不会被错误识别为终止指令。
- 20 个固定种子的随机依赖程序：每个 600 条混合运算，期间多次检查所有工作寄存器，合计 5,020 项检查。
- 合计 **21 个程序、6,472 项检查通过**；定向程序后追加的一项 ADDI 回归已在课程仿真器上单独重跑通过。
- 独立 `tests/axi_stress.cpp` 使用分别随机阻塞的 AR/AW/W 通道和随机延迟响应，对 VALID 与 payload 的停顿稳定性进行断言；完整 21 个程序使用不同总线随机种子验证。

## 参数化配置

除默认 `issueWidth=4, ROB=16, RS=16, ALU=4, sets=128, ways=4` 外，下列两套 RTL 均完成生成、Verilator 构建，并通过定向程序与随机种子 0、1、2：

| 配置 | 发射宽度 | ROB | RS | ALU | Cache sets | Cache ways |
|---|---:|---:|---:|---:|---:|---:|
| config1 | 1 | 8 | 8 | 1 | 8 | 1 |
| config2 | 2 | 8 | 12 | 2 | 16 | 3 |

## 尚未验证

未运行课程面积综合、静态时序或线上隐藏测试。尚无 Store-to-Load forwarding、独立 PRF、write-back、混合预测器、多条退休和多笔 AXI 未完成事务；这些是后续功能/性能工作，不应由上述功能测试结果推断已经完成。
