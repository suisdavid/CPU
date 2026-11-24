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

总面积尚未完成，不提供推测值。原 packed RTL 经 sv2v 转换后的 opt 综合，在门级展开时被系统以 SIGKILL 终止；此前内存持续增长，疑似触及环境内存限制。记录见 [packed-opt-attempt.json](measurements/packed-opt-attempt.json)。

当前使用同一份 Chisel 源码，由 `tests/GenerateArea.scala` 以 `--preserve-aggregate=none` 输出展开式 RTL，再由 sv2v v0.0.13 处理剩余数组语法。CPU 架构、参数和提交用 RTL 未改动。这个面积测量输入已通过 6 个性能用例、21 个整机自检程序（6,510 项检查），结果和周期数与原版完全一致；这属于回归验证，不是形式等价证明。校验信息见 [flat-validation.json](measurements/flat-validation.json)。

使用课程固定的 Yosys 0.63（`70a11c6bf0e8dd669f56c7da3587f78b405138e2`）、对应 ABC 和 ASAP7 RVT TT 库；SRAM 采用原课程 FakeRAM 模型，计入总面积。ABC 目标周期为 2 ns；本次不执行 OpenSTA，因此不报告已验证频率。

为防止环境重连丢失进度，`tests/resume_area.py` 将相同的 Yosys 0.63 opt 优化命令按阶段执行，并保存带校验的 RTLIL 检查点。它不跳过优化阶段；序列化可能影响优化顺序，不能承诺与一次性运行的映射逐门一致。

按综合前实例配置核对，数据与 tag SRAM 共 136,704 bit，宏面积为 5,740.255672 µm²。这只是 SRAM 部分，不是 CPU 总面积；最终结果以映射后保留的实例重新汇总。
