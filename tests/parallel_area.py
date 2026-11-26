#!/usr/bin/env python3
"""Resume independent per-module ABC mappings concurrently, then check the full design.

Requires completed module techmap and dfflibmap checkpoints. A completed serial
ABC prefix can be retained with --abc-prefix. The ABC command and library set
are unchanged; only selected modules are exported and replaced in the baseline.
Do not run alongside hierarchical_area.py in the same output directory.
"""
import argparse
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import json
from pathlib import Path

from hierarchical_area import atomic_json, checkpoint_steps, word_cells
from measure_area import MODEL, RAM_SOURCE, area_report, executable, identity, quote, run_yosys
from resume_area import configuration


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--script", type=Path, required=True)
    parser.add_argument("--yosys", default="yosys")
    parser.add_argument("--jobs", type=int, default=2)
    parser.add_argument("--abc-prefix", type=int, default=0)
    args = parser.parse_args()
    if args.jobs < 1:
        parser.error("--jobs must be positive")
    yosys = executable(args.yosys)
    out, version, stages, identities, signature = configuration(args.script, yosys)
    if "flatten" in stages[0]:
        parser.error("Requires hierarchy preparation")
    cp = out / "checkpoints"
    state = json.loads((cp / "state.json").read_text())
    source = cp / "stage-05.il"
    if (state["signature"] != signature or state["completed_stage"] != 5
            or identity(source)["sha256"] != state["checkpoint_sha256"]):
        parser.error("Pre-techmap checkpoint mismatch")
    modules = sorted(word_cells(source))
    if not 0 <= args.abc_prefix <= len(modules):
        parser.error("Invalid --abc-prefix")
    required = [cp / f"module-{i:02d}.json" for i in range(len(modules))]
    required += [cp / "libmap-00.json"]
    required += [cp / f"abc-{i:02d}.json" for i in range(args.abc_prefix)]
    if not all(p.exists() for p in required):
        parser.error("Required completed checkpoints are missing")
    history, source = checkpoint_steps(yosys, version, cp, "module", source, modules,
        lambda module: ["select " + module, "techmap", "opt_clean -purge", "opt -fast",
                        "opt_clean -purge", "select *"])
    abc_index = next(i for i, line in enumerate(stages[8]) if line.startswith("abc "))
    prepared, source = checkpoint_steps(yosys, version, cp, "libmap", source,
        ["dfflibmap"], lambda _: [*stages[7], *stages[8][:abc_index]])
    serial = []
    if args.abc_prefix:
        serial, source = checkpoint_steps(yosys, version, cp, "abc", source,
            modules[:args.abc_prefix],
            lambda module: ["select " + module, stages[8][abc_index], "clean -purge", "select *"])
    baseline = identity(source)
    result = out / "parallel-result"
    result.mkdir(exist_ok=True)

    def map_module(number):
        module = modules[number]
        name = f"abc-{number:02d}"
        output = result / f"{name}.il"
        temporary = output.with_suffix(".tmp.il")
        receipt_path = result / f"{name}.json"
        commands = ["read_rtlil " + quote(source), "select " + module,
                    stages[8][abc_index], "clean -purge",
                    "write_rtlil -selected " + quote(temporary)]
        fingerprint = hashlib.sha256(json.dumps([baseline, commands, version],
                                                sort_keys=True).encode()).hexdigest()
        if receipt_path.exists():
            receipt = json.loads(receipt_path.read_text())
            if (receipt["signature"] != fingerprint or receipt["input"] != baseline
                    or receipt["commands"] != commands or receipt["output"] != identity(output)):
                raise ValueError(f"Stale parallel checkpoint: {module}")
            print(f"Reusing mapped module: {module}", flush=True)
            return receipt
        print(f"Mapping independently: {module}", flush=True)
        run_yosys(yosys, result, name, commands)
        defined = []
        with temporary.open() as stream:
            for line in stream:
                if line.startswith("module "):
                    defined.append(line.split()[1].removeprefix("\\"))
        if defined != [module] or word_cells(temporary):
            raise ValueError(f"Invalid selected-module export: {module}")
        temporary.replace(output)
        receipt = {"module": module, "signature": fingerprint, "input": baseline,
                   "output": identity(output), "commands": commands}
        atomic_json(receipt_path, receipt)
        print(f"Saved mapped module: {module}", flush=True)
        return receipt

    mapped = {}
    with ThreadPoolExecutor(max_workers=args.jobs) as pool:
        futures = {pool.submit(map_module, i): i for i in range(args.abc_prefix, len(modules))}
        for future in as_completed(futures):
            mapped[futures[future]] = future.result()
    parallel = [mapped[i] for i in sorted(mapped)]
    if identity(source) != baseline:
        raise ValueError("Baseline changed during parallel mapping")
    commands = ["read_rtlil " + quote(source)]
    commands += ["read_rtlil -overwrite " + quote(Path(r["output"]["path"])) for r in parallel]
    commands += ["select *", *stages[8][abc_index + 1:]]
    for filename in ("stat.json", "design.json", "mapped.v"):
        commands = [line.replace(quote(out / filename), quote(result / filename)) for line in commands]
    print("Checking merged mapped design and computing area", flush=True)
    run_yosys(yosys, result, "map", commands)
    macros = json.loads((out / "ram/manifest.json").read_text())["macros"]
    area = area_report(json.loads((result / "design.json").read_text()),
                       json.loads((result / "stat.json").read_text()), macros)
    report = {"mode": "hierarchy-parallel-checkpoints", "area": area, "sram_model": MODEL,
              "timing_analyzed": False, "abc_target_period_ns": 2.0,
              "execution": "Hierarchy preserved, no opt_hier, per-module techmap with extra cleanup; independent ABC module exports merged before full mapped checks",
              "unmodified_course_opt": False, "base_flow_signature": signature,
              "tools": {"yosys": version}, "inputs": identities + [identity(RAM_SOURCE)],
              "pre_techmap_checkpoint": state, "modules": history, "dfflibmap": prepared,
              "abc_modules": serial, "parallel_baseline": baseline, "parallel_jobs": args.jobs,
              "abc_parallel_modules": parallel, "final_commands": commands,
              "mapped_verilog": identity(result / "mapped.v")}
    atomic_json(result / "report.json", report)
    print(json.dumps({k: v for k, v in area.items()
                      if k not in ("module_tree", "sram_instances")}, indent=2))
    print(f"Report: {result / 'report.json'}", flush=True)


if __name__ == "__main__":
    main()
