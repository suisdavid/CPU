# 验证记录

验证日期：2026-11-23。被验证 RTL 的 SHA-256：

`f3e02166b5050124040ae7d220173a261e211464c9ca94fe4faac02e990de329`

源码重新生成 RTL 后，生成文件与已构建版本一致。课程仿真器、SRAM 模型、官方程序及答案均未修改。

## 工具与条件

- Scala CLI 1.12.3；Scala 2.13.12；Chisel/chisel-plugin 6.4.0；chiseltest 6.0.0。
- 本机使用 OpenJDK 21.0.13（命令加 `--jvm system`）；仓库原有 JVM 17 声明保留。
- firtool 1.62.0；Verilator 5.020；原始 Config 配置。
- 官方测试版本：`29f980727f7d99a1842a58f34091c7579ba3fe85`。
- 官方测试条件：`MAX_CYCLES=100000000`、`LATENCY=10`。通过原课程 `scripts/testcase.py` 的输入构造、执行和答案比较函数验证，三个独立用例并行运行。
- 本机 chiseltest/Verilator 的预编译头兼容问题通过在本机 `verilated.mk` 中直接包含 `__pch.h` 解决；未修改 DUT 或测试判定。

## 编译与模块

- Scala 编译、Chisel 展开、firtool 生成、Verilator 构建通过。
- 生成 RTL 构建时：无 WIDTHEXPAND、UNOPTFLAT 警告。
- 加法、减法、有符号/无符号比较：224 对边界和随机操作数。
- 原两级部分积乘法器：448 个连续高/低半部请求以及清空测试。
- 原 32 级除法器：448 个连续商/余数请求，包括除零，以及清空测试。
- ALU 保留站：年轻就绪指令先执行、CDB 唤醒、40 轮入队/唤醒/出队，覆盖多次环形队列回绕。

## 整机自检与总线

| 仿真器 | 程序数 | 检查数 | 结果 |
| --- | ---: | ---: | --- |
| 原课程 AXI 从机 | 21 | 6,510 | 全通过 |
| 独立随机 AXI 从机 | 21 | 6,510 | 全通过 |

每套包含 1 个定向程序（1,490 项检查）和 20 个固定随机种子的程序（每个 251 项检查）。覆盖 RV32IM 算术、立即数、x0、同组依赖、JAL/JALR、分支、环形队列回绕、自然对齐的字节/半字/字访存、冷部分写、脏行冲突替换、部分转发合并及未就绪老 store。

独立从机对 AR/AW/W 分别施加随机阻塞，并延迟 R/B 响应；检查阻塞期间 VALID/载荷稳定、读写地址对齐、重复事务和 MMIO 完整写掩码。测试直接链接与课程仿真器相同的已构建 RTL 模型；也可用 `tests/build_stress.py` 独立重建。

## 官方正确性

按用户要求排除 `pi` 后，18/18 通过，周期数如下。`pi` 的完整测试已取消，不计入通过或失败。

| 用例 | 结果 | 周期数 |
| --- | --- | ---: |
| add_to_100 | PASS | 956 |
| array_test1 | PASS | 1,273 |
| array_test2 | PASS | 1,467 |
| basicopt1 | PASS | 638,792 |
| bulgarian | PASS | 431,231 |
| expr | PASS | 1,441 |
| gcd | PASS | 1,067 |
| hanoi | PASS | 9,695 |
| lvalue2 | PASS | 653 |
| magic | PASS | 722,354 |
| manyarguments | PASS | 672 |
| multiarray | PASS | 4,414 |
| naive | PASS | 633 |
| qsort | PASS | 2,699,701 |
| queens | PASS | 463,176 |
| statement_test | PASS | 2,723 |
| superloop | PASS | 816,880 |
| tak | PASS | 1,791,010 |

**周期限制说明：**其中 qsort, tak 超过默认 1,000,000 周期。按这些实际运行周期，默认上限只容纳 16/18 项；本次不能声称默认 `make test` 或线上固定周期限制全部通过。仓库默认周期限制未被提高。使用下面的显式命令复现完整功能验证：

```sh
for case_dir in testcases/correctness_*; do
  [ "${case_dir##*/}" = correctness_pi ] && continue
  python3 scripts/testcase.py --case "${case_dir##*/}" --sim build/sim --max-cycles 100000000 --latency 10
done
```

未运行综合、静态时序、性能评分或线上隐藏测试。其他发射宽度、队列深度和 Cache 配置未验证；非对齐访存不属于课程要求。
