#!/usr/bin/env python3
"""Resume the course Yosys 0.63 opt flow at verified RTLIL checkpoints.

Run measure_area.py first to prepare synth.ys and SRAM libraries. This expands
the exact `synth -top student_top -noabc -flatten` command from the pinned
Yosys source into stages; no optimization pass is omitted. Intermediate RTLIL
serialization may change optimization ordering, so this is identified in the
result rather than claimed to be a bit-identical monolithic mapping.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess

from measure_area import MODEL, RAM_SOURCE, area_report, executable, identity, quote, run_yosys

YOSYS_COMMIT = "70a11c6bf0e8dd669f56c7da3587f78b405138e2"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--script", type=Path, required=True)
    parser.add_argument("--yosys", default="yosys")
    args = parser.parse_args()
    yosys = executable(args.yosys)
    version = subprocess.check_output([yosys, "-V"], text=True).strip()
    if YOSYS_COMMIT not in version:
        parser.error("The checkpoint pass sequence requires the pinned course Yosys 0.63 commit")
    out = args.script.resolve().parent
    lines = args.script.read_text().splitlines()
    split = lines.index("synth -top student_top -noabc -flatten")
    # Verified against techlibs/common/synth.cc at YOSYS_COMMIT.
    stages = [
        lines[:split] + ["hierarchy -check -top student_top", "proc", "flatten",
                        "opt_expr", "opt_clean", "check"],
        ["opt -nodffe -nosdff"],
        ["fsm", "opt"],
        ["wreduce", "peepopt", "opt_clean", "alumacc", "share"],
        ["opt", "memory -nomap", "opt_clean"],
        ["opt -fast -full", "memory_map", "opt -full"],
        ["techmap"],
        ["opt -fast", "hierarchy -check", "stat", "check"],
        lines[split + 1:],
    ]
    input_paths = [Path(re.search(r'"([^"]+)"', line).group(1))
                   for line in lines[:split] if line.startswith("read_")]
    abc = Path(re.search(r'abc -exe "([^"]+)"', "\n".join(lines)).group(1))
    identities = [identity(p) for p in [*input_paths, abc, out / "student_top.v"]]
    signature = hashlib.sha256(json.dumps([version, stages, identities], sort_keys=True).encode()).hexdigest()
    checkpoints = out / "checkpoints"
    checkpoints.mkdir(exist_ok=True)
    manifest = checkpoints / "state.json"
    if manifest.exists():
        state = json.loads(manifest.read_text())
        if state["signature"] != signature:
            parser.error("Inputs or commands changed; use a new output directory")
    else:
        state = {"signature": signature, "completed_stage": -1}
    completed = state["completed_stage"]
    if completed >= 0 and completed < len(stages) - 1:
        last = checkpoints / f"stage-{completed:02d}.il"
        if identity(last)["sha256"] != state["checkpoint_sha256"]:
            parser.error("Last checkpoint is missing or its checksum changed")
    for index in range(completed + 1, len(stages)):
        commands = ([] if index == 0 else
                    ["read_rtlil " + quote(checkpoints / f"stage-{index - 1:02d}.il")])
        commands += stages[index]
        checkpoint = checkpoints / f"stage-{index:02d}.il"
        temporary = checkpoint.with_suffix(".tmp.il")
        if index < len(stages) - 1:
            commands.append("write_rtlil " + quote(temporary))
        print(f"Running stage {index + 1}/{len(stages)}: {stages[index][0]}", flush=True)
        run_yosys(yosys, checkpoints, f"stage-{index:02d}", commands)
        state = {"signature": signature, "completed_stage": index}
        if index < len(stages) - 1:
            temporary.replace(checkpoint)
            state["checkpoint_sha256"] = identity(checkpoint)["sha256"]
        temp_manifest = manifest.with_suffix(".tmp")
        temp_manifest.write_text(json.dumps(state, indent=2) + "\n")
        temp_manifest.replace(manifest)
        print(f"Saved stage {index + 1}/{len(stages)}", flush=True)
    macros = json.loads((out / "ram/manifest.json").read_text())["macros"]
    area = area_report(json.loads((out / "design.json").read_text()),
                       json.loads((out / "stat.json").read_text()), macros)
    report = {"mode": "opt", "area": area, "sram_model": MODEL,
              "timing_analyzed": False, "abc_target_period_ns": 2.0,
              "execution": "Yosys 0.63 default opt passes with RTLIL checkpoints",
              "checkpoint_signature": signature, "tools": {"yosys": version},
              "inputs": identities + [identity(RAM_SOURCE)],
              "stages": stages}
    (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({k: v for k, v in area.items()
                      if k not in ("module_tree", "sram_instances")}, indent=2))
    print(f"Report: {out / 'report.json'}")


if __name__ == "__main__":
    main()
