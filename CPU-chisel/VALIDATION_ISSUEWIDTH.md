# 多提交架构验证记录

验证日期：2026-11-25。`issueWidth=4`，提交用 RTL SHA-256：

`f3843a649f09385b383eb00ea9c63e384618abd9ecba186f049e70a614eb16a1`

硬件源码仅修改 `Committer.scala` 与 `CPU.scala`，并重新生成提交 RTL。课程仿真器、SRAM 模型、官方程序和答案保持原样。

## 架构边界

- 按 ROB 顺序每周期最多退休 `issueWidth` 条，未就绪指令阻塞年轻指令。
- 多路寄存器回写及提交旁路按年龄覆盖；同周期新重命名不被旧提交清除；x0 不写入。
- 保留单个 store 事务与单个预测器更新端口。store 应答前不退休，store 提交后结束当前组；每周期最多训练一个分支，第二个分支等待下周期。
- 误预测分支及 halt 阻止同组所有年轻副作用；清空周期不产生提交。

## 工具和检查

Scala 2.13.12、Chisel/chisel-plugin 6.4.0、chiseltest 6.0.0、firtool 1.62.0、Verilator 5.020；本机 OpenJDK 21.0.13.1，命令使用 `--jvm system`。

- Scala 编译、Chisel/firtool 生成、原课程 Verilator 构建通过，未出现 WIDTHEXPAND 或 UNOPTFLAT。
- Committer 定向测试通过：4 条退休、同时入队/出队、40 轮回绕、从槽 15 开始的跨环提交、CDB 未就绪缺口、store 载荷保持/应答、多个分支顺序、每个槽的误预测截断、halt 和 x0。
- 普通 AXI 和独立随机阻塞 AXI 各 **21 个程序、6,574 项自检全部通过**。定向程序新增 64 项 WAW/RAW 检查，在老 DIV 阻塞期间积累年轻就绪指令，以覆盖多提交与新重命名重叠。
- 官方性能测试 **6/6 通过**，GEOMEAN IPC **0.302530302**。数据和对比见 [PERFORMANCE.md](PERFORMANCE.md)。
- 本次没有重新运行未改动的算术/RS 模块测试；其历史结果见 [VALIDATION.md](VALIDATION.md)。

## 官方正确性

测试版本 `29f980727f7d99a1842a58f34091c7579ba3fe85`，`LATENCY=10`、`MAX_CYCLES=100000000`，排除 `pi` 后 **18/18 通过**。

| 用例 | 周期数 | 结果 |
| --- | ---: | --- |
| add_to_100 | 954 | PASS |
| array_test1 | 1,271 | PASS |
| array_test2 | 1,465 | PASS |
| basicopt1 | 623,733 | PASS |
| bulgarian | 431,540 | PASS |
| expr | 1,393 | PASS |
| gcd | 1,066 | PASS |
| hanoi | 9,601 | PASS |
| lvalue2 | 651 | PASS |
| magic | 691,341 | PASS |
| manyarguments | 672 | PASS |
| multiarray | 4,247 | PASS |
| naive | 631 | PASS |
| qsort | 2,629,393 | PASS |
| queens | 430,301 | PASS |
| statement_test | 2,647 | PASS |
| superloop | 811,347 | PASS |
| tak | 1,884,271 | PASS |

`qsort` 和 `tak` 仍超过默认 1,000,000 周期；不能声称默认周期限制全部通过。完整数据见 [correctness-issuewidth.json](measurements/correctness-issuewidth.json)。

## 复现

```sh
scala-cli run CPU-chisel --server=false -M cpu2026.CommitterTests
scala-cli run CPU-chisel --server=false -M cpu2026.Generate
python3 scripts/build.py --out build/multicommit
python3 tests/regression.py --sim build/multicommit/sim
python3 scripts/testcase.py --kind perf --sim build/multicommit/sim --max-cycles 100000000 --latency 10
for case_dir in testcases/correctness_*; do
  [ "${case_dir##*/}" = correctness_pi ] && continue
  python3 scripts/testcase.py --case "${case_dir##*/}" --sim build/multicommit/sim --max-cycles 100000000 --latency 10
done
python3 tests/build_stress.py --out build/multicommit-stress
python3 tests/regression.py --sim build/multicommit-stress/sim --stress
```

本机随机 AXI 测试复用了课程仿真器构建产生的同一份 Verilator RTL 模型，再链接 `tests/axi_stress.cpp`；上述独立重建命令也可复现。其他发射宽度未验证，未运行 OpenSTA 或线上隐藏测试。
