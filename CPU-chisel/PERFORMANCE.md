# 面积与 IPC 测量

当前版本：2026-11-25 按用户要求将 Committer 扩为最多 `issueWidth=4` 条按序提交。提交用 RTL SHA-256：`f3843a649f09385b383eb00ea9c63e384618abd9ecba186f049e70a614eb16a1`。实现与验证见 [VALIDATION_ISSUEWIDTH.md](VALIDATION_ISSUEWIDTH.md)。

## IPC：单提交与四提交对比

同一套官方 `perf_*` 测试、`LATENCY=10`、`MAX_CYCLES=100000000`，两版均 6/6 答案比较通过且实际均少于默认 100 万周期。指令数来自课程 `metrics.json`（包含退出 MMIO store），周期数来自课程仿真器；使用各用例 IPC 的几何平均。`pi` 不在性能测试集合中，按用户要求不运行。

| 性能用例 | 指令数 | 单提交周期 | 四提交周期 | 四提交 IPC | IPC 变化 |
| --- | ---: | ---: | ---: | ---: | ---: |
| median | 6,961 | 33,567 | 33,152 | 0.209972 | +1.25% |
| multiply | 21,722 | 37,300 | 36,238 | 0.599426 | +2.93% |
| qsort | 139,900 | 446,528 | 434,035 | 0.322324 | +2.88% |
| rsort | 195,719 | 756,176 | 750,111 | 0.260920 | +0.81% |
| towers | 5,278 | 12,529 | 11,984 | 0.440421 | +4.55% |
| vvadd | 4,524 | 27,243 | 27,509 | 0.164455 | -0.97% |

**GEOMEAN IPC：0.296910102 → 0.302530302，提升 1.8929%。** 当前仍低于仓库第一档的 0.6000 要求。`vvadd` 周期略增；加宽提交不保证每项程序都加速。本次未测停顿计数器，不据此断言其他瓶颈的位置。

Committer 满宽退休已通过单元测试；未就绪、误预测、halt、store 以及第二个分支会截断当前提交组，详见验证记录。

原始数据：[单提交 ipc.json](measurements/ipc.json)、[四提交 ipc-issuewidth.json](measurements/ipc-issuewidth.json)。

```sh
python3 scripts/testcase.py --kind perf --sim build/multicommit/sim --max-cycles 100000000 --latency 10
```

## 当前版面积

面积综合正在进行，尚无可报告的 CPU 总面积。使用同一份 Chisel 源码，经 `tests/GenerateArea.scala` 的 `--preserve-aggregate=none` 和 sv2v v0.0.13 转换。仅改变 RTL 表示，硬件配置与提交用 RTL 不因测量而改变。

使用课程固定 Yosys 0.63（`70a11c6bf0e8dd669f56c7da3587f78b405138e2`）、对应 ABC、ASAP7 RVT TT 和 FakeRAM。当前流程保留模块层次、跳过出现端口错误的 `opt_hier`，逐模块门级展开后清理并保存检查点。这是单独的面积测量模式，**不是默认扁平化 opt 的评测值**。ABC 目标周期 2 ns；未运行 OpenSTA，不报告已验证频率。

复现流程（工具路径按本机安装位置传入）：

```sh
scala-cli run CPU-chisel tests/GenerateArea.scala --server=false -M cpu2026.GenerateArea -- build/multicommit-area-input
python3 tests/measure_area.py --mode hierarchy --prepare-only --filelist build/multicommit-area-input/filelist.f --out build/multicommit-area --yosys "$YOSYS" --abc "$ABC" --sv2v "$SV2V" --asap7-lib "$ASAP7_LIB"
python3 tests/resume_area.py --script build/multicommit-area/hierarchy/synth.ys --yosys "$YOSYS" --stop-before-techmap
python3 tests/hierarchical_area.py --script build/multicommit-area/hierarchy/synth.ys --yosys "$YOSYS"
```

此前单提交版：默认扁平化及分阶段 opt 被 SIGKILL 终止，疑似内存不足；课程 diagnose 的 `opt_hier` 报 `Port io_cout connected on instance adder3 not found in module Adder or width is not matching`。历史 SRAM 配置模型面积 5,740.255672 µm² 只是 SRAM 部分，不能作为 CPU 总面积。旧版输入验证见 [flat-validation.json](measurements/flat-validation.json)，旧版完整面积未取得，无法作总面积前后比较。
