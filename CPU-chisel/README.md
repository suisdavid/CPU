# 在原稿上修复 CPU

基于 `56ceb004d3fec975d5c33a937f12bef32b6453da`，沿用原文件和模块：Decoder、Issuer、Executer/ALU/LSU/BRU、Committer、Predictor、Cache、CPU、StudentTop。

保留四发射、四 ALU、环形 RS/ROB、共享两级部分积乘法器与 32 级除法器、ROB 中 store-to-load forwarding、LHT/PHT 与 Gshare 混合预测、四个 I-cache bank、四路 SRAM Cache、LRU 和脏块回写。Config 中容量与发射配置保持原样。CPU 直接控制 AXI。

修复包括立即数位段、类型/拼写、默认连线、x0/同组依赖/CDB 与退休旁路、JALR 旧操作数、RS 队列移位、乘除法进位及符号边界、按字节转发、部分写缺失、替换脏行、独立 AW/W 握手和回滚后的事务排空。

Cache 元数据按 way/set 表示，并让每路每种元数据只有一个索引写入口；容量、查找与替换算法不变。RS/ROB 广播按物理槽更新，保留环形队列与从老到新的选择顺序，避免动态写展开为大量重复逻辑。

## 复现

```sh
scala-cli run CPU-chisel --server=false -M cpu2026.Generate
make build APPIMAGE=
scala-cli run CPU-chisel --server=false -M cpu2026.ModuleTests
python3 tests/regression.py --sim build/sim --seeds 20
python3 scripts/testcase.py --kind correctness --testcases testcases --sim build/sim --max-cycles 100000000
python3 tests/build_stress.py
python3 tests/regression.py --sim build-stress/sim --seeds 20 --stress
```

当前是恢复后的修复检查点，完整编译与回归仍在进行。以前的测试记录不能替代对本次代码的验证。未做综合、静态时序或隐藏测试。
