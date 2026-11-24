# 面积与 IPC 测量

被测提交：`d55e690d7152e8819436135797ee67c918dec924`。RTL SHA-256：`f3e02166b5050124040ae7d220173a261e211464c9ca94fe4faac02e990de329`。

## IPC

使用原课程 `scripts/testcase.py --kind perf`，6 个性能用例均通过答案比较。总线延迟 `LATENCY=10`，显式运行上限 `MAX_CYCLES=100000000`；本次 6 个用例实际均少于默认 1,000,000 周期。按用户要求排除 `pi`；它属于 correctness 测试，不在这 6 个性能用例中。

指令数来自课程 `metrics.json` 的 `dynamic_instructions`，包含最后的退出 MMIO store；周期数由原课程仿真器实测。IPC 为动态退休指令数除以周期数，不将推测执行后被清空的指令计入。综合 IPC 使用各用例 IPC 的几何平均，不使用算术平均或总指令数/总周期数。

| 用例 | 动态指令数 | 周期数 | IPC | 正确性 |
| --- | ---: | ---: | ---: | --- |
| median | 6,961 | 33,567 | 0.207376 | PASS |
| multiply | 21,722 | 37,300 | 0.582359 | PASS |
| qsort | 139,900 | 446,528 | 0.313306 | PASS |
| rsort | 195,719 | 756,176 | 0.258827 | PASS |
| towers | 5,278 | 12,529 | 0.421263 | PASS |
| vvadd | 4,524 | 27,243 | 0.166061 | PASS |

**IPC 几何平均值：0.296910102。**

复现：

```sh
make perf SIM=build/sim MAX_CYCLES=100000000 LATENCY=10
```

当前仓库 `README-ZH.md` 列出的第一档 IPC 要求为 0.6000，本次 0.2969 未达到该要求。当前 Committer 每周期最多退休一条指令，因此即使四发射，持续退休 IPC 的结构上限仍为 1；这是代码结构分析，不是通过计数器测出的停顿占比。

原始数据见 [measurements/ipc.json](measurements/ipc.json)。

## 面积

综合尚未完成，目前不提供总面积数字。测量使用课程固定的 Yosys 0.63（`70a11c6bf0e8dd669f56c7da3587f78b405138e2`）、对应 ABC 和 ASAP7 RVT TT 库；SRAM 采用原课程 FakeRAM 模型并计入总面积。可复现入口为 `tests/measure_area.py`，采用课程 opt 模式的相同映射命令，ABC 目标周期为 2 ns；此脚本不执行 OpenSTA。

原 RTL 在 Yosys 的内联 `packed struct` 端口语法处报错（`student_top.sv:851: unexpected TOK_STRUCT`）。测量前使用 sv2v v0.0.13 转换为 Verilog，未修改 CPU 源码或提交用 RTL。转换后的模型已通过 6 个性能用例与 21 个整机自检程序（6,510 项检查），结果和周期数与原 RTL 完全一致；这属于回归验证，不是形式等价证明。记录见 [normalization-validation.json](measurements/normalization-validation.json)。
