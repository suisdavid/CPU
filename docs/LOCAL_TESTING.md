# 本地 IPC 测试和综合

适用分支：`codex/repair-original-chisel`。所有工程内命令均从仓库根目录执行，不是从 `CPU-chisel/` 执行。

## 1. 代码和环境

新克隆：

```bash
git clone --branch codex/repair-original-chisel --recurse-submodules https://github.com/suisdavid/CPU.git
cd CPU
```

已有克隆：先提交或 stash 自己的未提交改动，再执行：

```bash
git fetch origin
git switch codex/repair-original-chisel
git pull --ff-only origin codex/repair-original-chisel
git submodule update --init --recursive
```

下面使用 Linux x86-64 Docker 环境。需要先安装并启动 Docker；macOS/Apple Silicon 上也指定 `linux/amd64`，因为这里下载的是 x86-64 sv2v。Apple Silicon 仿真执行会更慢。没有可用 Docker 时，可在 Linux x86-64 虚拟机/服务器中使用相同工具版本和工程命令。

```bash
# 课程工具链：Verilator、固定版本 Yosys/ABC、ASAP7、OpenSTA。
docker build --platform linux/amd64 --build-arg BUILD_JOBS=2 -t cpu2026 .
# 增加 JDK 21、Scala CLI 1.12.3、sv2v 0.0.13。
docker build --platform linux/amd64 -f Dockerfile.local -t cpu2026-local .
docker run --rm -it --platform linux/amd64 \
  -v "$PWD":/work -w /work \
  -v cpu2026-local-cache:/root/.cache \
  cpu2026-local bash
```

后续命令都在容器的 `/work` 中运行。首轮构建和依赖下载需要联网，后续重用镜像及缓存。新加的 `Dockerfile.local` 已检查命令和固定下载地址，但本次交接未重新构建镜像。

```bash
set -euo pipefail
java -version
scala-cli version
verilator --version
yosys -V
sv2v --version
sta -version
```

历史验证环境为 Scala 2.13.12、Chisel 6.4.0、firtool 1.62.0、Verilator 5.020。Scala/Chisel 版本由源码 directives 指定，`--jvm system` 使用容器的 JDK 21。课程 Dockerfile 固定 Yosys commit `70a11c6bf0e8dd669f56c7da3587f78b405138e2` 和对应 ABC。

## 2. IPC

仓库已提交生成好的四提交 RTL。未修改 Chisel 时可直接构建仿真器；修改过 Chisel 时先重新生成：

```bash
scala-cli run CPU-chisel --server=false --jvm system -M cpu2026.Generate
```

构建并运行六个官方性能用例：

```bash
make build APPIMAGE= VERILATOR=verilator BUILD=build/local JOBS=2
make perf APPIMAGE= SIM=build/local/sim MAX_CYCLES=100000000 LATENCY=10 \
  | tee build/local/ipc.txt
```

`SIM=` 复用刚刚构建的仿真器。终端列出每项 instructions、cycles、IPC，最后一行是 `GEOMEAN`；文本保存在 `build/local/ipc.txt`。历史实测为 **0.302530302**，终端四位小数显示 **0.3025**。六个性能用例均在默认 100 万周期内完成；`pi` 不属于性能集合。

只运行一个用例：

```bash
make perf APPIMAGE= SIM=build/local/sim Case=perf_qsort MAX_CYCLES=100000000 LATENCY=10
```

可选的正确性检查：

```bash
scala-cli run CPU-chisel --server=false --jvm system -M cpu2026.CommitterTests
python3 tests/regression.py --sim build/local/sim
for case_dir in testcases/correctness_*; do
  [ "${case_dir##*/}" = correctness_pi ] && continue
  python3 scripts/testcase.py --kind correctness --case "${case_dir##*/}" \
    --sim build/local/sim --max-cycles 100000000 --latency 10
done
```

正确性用例 `qsort`、`tak` 的历史周期数为 2,629,393 和 1,884,271，因此需要提高周期上限。

## 3. 课程默认综合：面积和时序

不能直接把当前包含聚合类型的提交 RTL 交给 Yosys 前端。先从同一 Chisel 源码生成无聚合 RTL，再用 sv2v 规范化语法，不修改 CPU 配置和架构：

```bash
scala-cli run CPU-chisel tests/GenerateArea.scala --server=false --jvm system \
  -M cpu2026.GenerateArea -- build/local-area-input
sv2v -D SYNTHESIS build/local-area-input/student_top.sv \
  > build/local-area-input/student_top.v
printf 'student_top.v\n' > build/local-area-input/yosys.f
```

运行课程原有 `MODE=opt` 综合及 OpenSTA：

```bash
make synth APPIMAGE= YOSYS=yosys ABC=yosys-abc STA=sta \
  ASAP7_LIB=/opt/asap7/lib \
  FILELIST=build/local-area-input/yosys.f \
  SYNTH_OUT=build/local-synth MODE=opt CLOCK_PERIOD_NS=2.0 \
  | tee build/local-synth.log
```

**只有成功执行到结束才有完整结果。** 输出为：

- `build/local-synth/opt/report.txt`：面积与时序摘要。
- `build/local-synth/opt/report.json`：完整结构化报告。
- `build/local-synth/opt/mapped.v`：映射网表。
- `build/local-synth/opt/timing.rpt`：时序路径。
- `build/local-synth/opt/synth.log`：综合日志。

此前约 9.7 GiB 环境中的默认扁平化流程曾被 SIGKILL 终止，疑似内存不足；所需峰值内存尚未确认。应给 Docker/虚拟机更充足的内存，不能保证未经实测的内存容量一定足够。`CLOCK_PERIOD_NS=2.0` 是目标，不代表已经达到 500 MHz。

## 4. 可续跑的层次化面积流程（可选）

该流程保留层次、跳过 `opt_hier`，逐模块清理并映射。**它不等同于默认 `MODE=opt` 的课程评测结果，不运行 OpenSTA。** 最终全设计映射与面积汇总尚未跑完，本次按用户要求停止，不声称端到端通过。

先执行上面的 `GenerateArea` 命令，随后：

```bash
python3 tests/measure_area.py --mode hierarchy --prepare-only \
  --filelist build/local-area-input/filelist.f --out build/local-area \
  --yosys yosys --abc yosys-abc --sv2v sv2v --asap7-lib /opt/asap7/lib
python3 tests/resume_area.py \
  --script build/local-area/hierarchy/synth.ys --yosys yosys --stop-before-techmap
python3 tests/hierarchical_area.py \
  --script build/local-area/hierarchy/synth.ys --yosys yosys --abc-limit 0
python3 tests/parallel_area.py \
  --script build/local-area/hierarchy/synth.ys --yosys yosys --abc-prefix 0 --jobs 2
```

资源紧张可将最后一步改为 `--jobs 1`；也可使用 `hierarchical_area.py` 不带 `--abc-limit` 完成全串行流程。不要在相同输出目录并发运行两个驱动脚本。

并行流程成功后，报告为 `build/local-area/hierarchy/parallel-result/report.json`，网表为同目录 `mapped.v`。串行输出到 `module-result/`。临时文件或单模块映射成功不代表已获得总面积。

同目录、相同输入和工具下，重跑对应续跑命令会校验并复用检查点。不要换路径后手改签名强行复用；更换源码、工具或工作目录时请换新输出目录从头开始。不要对已有续跑目录重跑准备命令，以免覆盖输入。
