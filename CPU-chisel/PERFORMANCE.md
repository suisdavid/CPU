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

## 2026-11-25 续跑状态

- 展开式 RTL 的分阶段 opt 综合已完成零起始编号 0–5 阶段；第 6 阶段 `techmap` 被 SIGKILL 终止，尚未生成映射面积。
- 尝试 `techmap -max_iter 1; opt -fast; clean -purge` 后，第一轮展开完成，但随后寄存器优化也被 SIGKILL 终止。观察到内存明显增长；无法取得内核 OOM 日志，因此内存耗尽仍为推断。
- 随后启动课程 `diagnose` 模式（`synth -top student_top -noabc -hieropt`），最后可读日志显示模块连接检查为 0 problems，正在优化。之后执行环境不可用，最终状态及面积尚未取得，不能记为综合通过。
- 续跑时优先检查 `build/area-hier/diagnose/report.json`、`synth.log` 和 `../cpu-tools/area-hier.log`。保留层次的面积应标注为 diagnose，不能直接当作默认 opt 模式的评测面积。

已完成的 opt 检查点：`build/area-flat/opt/checkpoints/stage-05.il`，SHA-256 为 `f170b64e0f30daa8350f2c79c0e0fc283b815af0b354f5e3fd4d4b22e51d70b8`；输入及命令签名为 `502e8d2f5e954a6d0edfc854f256502955266d91e95cda6538bd892713e908e5`。检查点未提交至 Git，恢复后必须先确认文件存在及校验值一致。这些尝试均未修改 CPU 硬件源码、配置或提交用 RTL。
