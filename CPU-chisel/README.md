# 在原稿上修复 CPU

基于 `56ceb004d3fec975d5c33a937f12bef32b6453da`，沿用原文件和模块：Decoder、Issuer、Executer/ALU/LSU/BRU、Committer、Predictor、Cache、CPU、StudentTop。

保留四发射、四 ALU、环形 RS/ROB、共享两级部分积乘法器与 32 级除法器、ROB 中 store-to-load forwarding、LHT/PHT 与 Gshare 混合预测、四个 I-cache bank、四路 SRAM Cache、LRU 和脏块回写。Config 中容量与发射配置保持原样。CPU 直接控制 AXI。

修复包括立即数位段、类型/拼写、默认连线、x0/同组依赖/CDB 与退休旁路、JALR 旧操作数、RS 队列移位、乘除法进位及符号边界、按字节转发、部分写缺失、替换脏行、独立 AW/W 握手和回滚后的事务排空。

Cache 元数据按 way/set 表示，并让每路每种元数据只有一个索引写入口；容量、查找与替换算法不变。RS/ROB 广播按物理槽更新，保留环形队列与从老到新的选择顺序，避免动态写展开为大量重复逻辑。

逐文件说明见 [REPAIR_NOTES.md](REPAIR_NOTES.md)。

## 复现

```sh
scala-cli run CPU-chisel --server=false -M cpu2026.Generate
make build APPIMAGE=
scala-cli run CPU-chisel --server=false -M cpu2026.ModuleTests
scala-cli run CPU-chisel --server=false -M cpu2026.CommitterTests
python3 tests/regression.py --sim build/sim --seeds 20
for case_dir in testcases/correctness_*; do
  [ "${case_dir##*/}" = correctness_pi ] && continue
  python3 scripts/testcase.py --case "${case_dir##*/}" --sim build/sim --max-cycles 100000000 --latency 10
done
python3 tests/build_stress.py
python3 tests/regression.py --sim build-stress/sim --seeds 20 --stress
```

编译与 RTL 生成通过；模块测试通过；官方正确性 18/18 通过（排除 `pi`，`MAX_CYCLES=100000000`、`LATENCY=10`）；普通与随机阻塞 AXI 各通过 21 个程序、6,574 项自检。

`qsort`、`tak` 超过默认 100 万周期，不能声称默认周期限制全部通过。当前 Committer 每周期最多按序退休 issueWidth（4）条，store 串行且每周期最多更新一个分支；完整周期与验证范围见 [VALIDATION_ISSUEWIDTH.md](VALIDATION_ISSUEWIDTH.md)，IPC 与面积见 [PERFORMANCE.md](PERFORMANCE.md)。
