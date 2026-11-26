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


def checkpoint_steps(yosys, version, checkpoints, prefix, source, items, passes, validate=None):
    """Verify receipt links and the final file; older intermediates may be pruned."""
    history = []
    previous = identity(source)
    for number, item in enumerate(items):
        name = f"{prefix}-{number:02d}"
        output = checkpoints / f"{name}.il"
        temporary = output.with_suffix(".tmp.il")
        receipt_path = checkpoints / f"{name}.json"
        commands = ["read_rtlil " + quote(source), *passes(item),
                    "write_rtlil " + quote(temporary)]
        fingerprint = hashlib.sha256(json.dumps([previous, commands, version],
                                               sort_keys=True).encode()).hexdigest()
        if receipt_path.exists():
            receipt = json.loads(receipt_path.read_text())
            if (receipt["signature"] != fingerprint or receipt["input"] != previous
                    or receipt["commands"] != commands
                    or receipt["output"]["path"] != str(output)):
                raise ValueError(f"Changed inputs/commands at {name}; refusing stale checkpoint")
            print(f"Reusing {prefix}: {item} ({number+1}/{len(items)})", flush=True)
        else:
            if identity(source) != previous:
                raise ValueError(f"Checkpoint missing or checksum changed: {source}")
            print(f"Running {prefix}: {item} ({number+1}/{len(items)})", flush=True)
            run_yosys(yosys, checkpoints, name, commands)
            if validate:
                validate(item, temporary)
            temporary.replace(output)
            receipt = {"module": item, "signature": fingerprint, "input": previous,
                       "output": identity(output), "commands": commands}
            atomic_json(receipt_path, receipt)
            if number > 0:
                source.unlink(missing_ok=True)
        history.append(receipt)
        source = output
        previous = receipt["output"]
    if identity(source) != previous:
        raise ValueError(f"Final checkpoint missing or checksum changed: {source}")
    return history, source


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--script", type=Path, required=True)
    parser.add_argument("--yosys", default="yosys")
    parser.add_argument("--abc-limit", type=int,
                        help="Save this many ABC modules, then stop for a later continuation")
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
    abc_count = len(modules) if args.abc_limit is None else args.abc_limit
    if not 0 <= abc_count <= len(modules):
        parser.error("--abc-limit must be between zero and the module count")
    def mapped_module(module, path):
        if word_cells(path).get(module):
            raise ValueError(f"Unmapped word-level cells remain in {module}")
    history, source = checkpoint_steps(yosys, version, checkpoints, "module", source, modules,
        lambda module: ["select " + module, "techmap", "opt_clean -purge", "opt -fast",
                        "opt_clean -purge", "select *"], mapped_module)
    if word_cells(source):
        raise ValueError("Unmapped word-level cells remain")
    result = out / "module-result"
    result.mkdir(exist_ok=True)
    abc_index = next(i for i, line in enumerate(stages[8]) if line.startswith("abc "))
    prepared, source = checkpoint_steps(yosys, version, checkpoints, "libmap", source,
        ["dfflibmap"], lambda _: [*stages[7], *stages[8][:abc_index]])
    abc_history, source = checkpoint_steps(yosys, version, checkpoints, "abc", source, modules[:abc_count],
        lambda module: ["select " + module, stages[8][abc_index], "clean -purge", "select *"])
    if abc_count < len(modules):
        print(f"Saved {abc_count} ABC modules; stopped for continuation", flush=True)
        return
    commands = ["read_rtlil " + quote(source), *stages[8][abc_index+1:]]
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
              "dfflibmap": prepared, "abc_modules": abc_history,
              "final_commands": commands, "mapped_verilog": identity(result / "mapped.v")}
    atomic_json(result / "report.json", report)
    print(json.dumps({k: v for k, v in area.items()
                      if k not in ("module_tree", "sram_instances")}, indent=2))
    print(f"Report: {result / 'report.json'}")


if __name__ == "__main__":
    main()
