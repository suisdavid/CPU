#!/usr/bin/env python3
"""Map the hierarchy one module at a time to limit peak synthesis memory.

Prepare with measure_area.py --mode hierarchy --prepare-only, then run
resume_area.py --stop-before-techmap. This avoids the pinned Yosys opt_hier
port error and preserves module boundaries. It is NOT the course opt result.
Extra cleanup occurs after each module's techmap, before its opt -fast pass.
"""
import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path

from measure_area import MODEL, RAM_SOURCE, area_report, executable, identity, quote, run_yosys
from resume_area import configuration


def atomic_json(path, value):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n")
    temporary.replace(path)


def word_cells(path):
    modules = Counter()
    module = None
    with path.open() as stream:
        for line in stream:
            fields = line.split(maxsplit=2)
            if fields and fields[0] == "module":
                module = fields[1].strip().removeprefix("\\")
            if len(fields) == 3 and fields[0] == "cell":
                kind = fields[1]
                if kind.startswith("$") and not kind.startswith("$_") and kind != "$scopeinfo":
                    modules[module] += 1
    return modules


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--script", type=Path, required=True)
    parser.add_argument("--yosys", default="yosys")
    args = parser.parse_args()
    yosys = executable(args.yosys)
    out, version, stages, identities, signature = configuration(args.script, yosys)
    if "flatten" in stages[0]:
        parser.error("Requires --mode hierarchy preparation")
    checkpoints = out / "checkpoints"
    state = json.loads((checkpoints / "state.json").read_text())
    source = checkpoints / "stage-05.il"
    if state["signature"] != signature or state["completed_stage"] != 5:
        parser.error("Requires verified pre-techmap checkpoint, stages 0-5")
    if identity(source)["sha256"] != state["checkpoint_sha256"]:
        parser.error("Pre-techmap checkpoint checksum mismatch")
    modules = sorted(word_cells(source))
    history = []
    for number, module in enumerate(modules):
        name = f"module-{number:02d}"
        output = checkpoints / f"{name}.il"
        temporary = output.with_suffix(".tmp.il")
        receipt_path = checkpoints / f"{name}.json"
        commands = ["read_rtlil " + quote(source), "select " + module,
                    "techmap", "opt_clean -purge", "opt -fast", "opt_clean -purge",
                    "select *", "write_rtlil " + quote(temporary)]
        fingerprint = hashlib.sha256(json.dumps([identity(source), commands, version],
                                               sort_keys=True).encode()).hexdigest()
        if receipt_path.exists():
            receipt = json.loads(receipt_path.read_text())
            if receipt["signature"] != fingerprint or receipt["output"] != identity(output):
                parser.error(f"Changed inputs/output at {name}; refusing stale checkpoint")
            print(f"Reusing {module} ({number+1}/{len(modules)})", flush=True)
        else:
            print(f"Mapping {module} ({number+1}/{len(modules)})", flush=True)
            run_yosys(yosys, checkpoints, name, commands)
            if word_cells(temporary).get(module):
                raise ValueError(f"Unmapped word-level cells remain in {module}")
            temporary.replace(output)
            receipt = {"module": module, "signature": fingerprint, "input": identity(source),
                       "output": identity(output), "commands": commands}
            atomic_json(receipt_path, receipt)
        history.append(receipt)
        source = output
    if word_cells(source):
        raise ValueError("Unmapped word-level cells remain")
    result = out / "module-result"
    result.mkdir(exist_ok=True)
    commands = ["read_rtlil " + quote(source), *stages[7], *stages[8]]
    for filename in ("stat.json", "design.json", "mapped.v"):
        commands = [line.replace(quote(out / filename), quote(result / filename))
                    for line in commands]
    print("Mapping to ASAP7 and checking mapped design", flush=True)
    run_yosys(yosys, result, "map", commands)
    macros = json.loads((out / "ram/manifest.json").read_text())["macros"]
    area = area_report(json.loads((result / "design.json").read_text()),
                       json.loads((result / "stat.json").read_text()), macros)
    report = {"mode": "hierarchy-module-checkpoints", "area": area, "sram_model": MODEL,
              "timing_analyzed": False, "abc_target_period_ns": 2.0,
              "execution": "Hierarchy preserved, no opt_hier, per-module techmap with extra cleanup",
              "unmodified_course_opt": False, "base_flow_signature": signature,
              "tools": {"yosys": version}, "inputs": identities + [identity(RAM_SOURCE)],
              "pre_techmap_checkpoint": state, "modules": history,
              "final_commands": commands, "mapped_verilog": identity(result / "mapped.v")}
    atomic_json(result / "report.json", report)
    print(json.dumps({k: v for k, v in area.items()
                      if k not in ("module_tree", "sram_instances")}, indent=2))
    print(f"Report: {result / 'report.json'}")


if __name__ == "__main__":
    main()
