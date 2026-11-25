#!/usr/bin/env python3
"""Run the course ASAP7/FakeRAM area flow, without requiring OpenSTA.

sv2v only normalizes SystemVerilog syntax; no CPU parameters or RTL logic are
changed. This reports mapped cell area, not placed/routed silicon area or timing.
"""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "scripts"))
from build import read_sources
from fakeram import MODEL, RAM_SOURCE, prepare_memories
from synth import quote, run_yosys
from synth_report import area_report
from toolchain import executable


def identity(path):
    return {"path": str(path), "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--yosys", default="yosys")
    parser.add_argument("--abc", default="yosys-abc")
    parser.add_argument("--sv2v", default="sv2v")
    parser.add_argument("--asap7-lib", type=Path, required=True)
    parser.add_argument("--filelist", type=Path, default=ROOT / "verilog/filelist.f")
    parser.add_argument("--out", type=Path, default=ROOT / "build/area")
    parser.add_argument("--mode", choices=("opt", "diagnose", "hierarchy"), default="opt",
                        help="hierarchy preserves modules without the course opt_hier pass")
    parser.add_argument("--prepare-only", action="store_true",
                        help="Prepare elaboration, SRAM models and synth.ys for checkpointed runs")
    args = parser.parse_args()
    yosys, abc, sv2v = map(executable, (args.yosys, args.abc, args.sv2v))
    sources = [p for p in read_sources(args.filelist) if p != RAM_SOURCE]
    libs = sorted(args.asap7_lib.resolve().glob("*.lib"))
    if len(libs) != 5:
        parser.error("Exactly five course ASAP7 RVT TT libraries are required")
    out = args.out.resolve() / args.mode
    out.mkdir(parents=True, exist_ok=True)
    for name in ("report.json", "area.json"):
        (out / name).unlink(missing_ok=True)
    normalized = out / "student_top.v"
    with normalized.open("w") as stream:
        subprocess.run([sv2v, "-D", "SYNTHESIS", *map(str, sources)],
                       stdout=stream, check=True)
    # Same elaboration, macro preparation, and mapping commands as scripts/synth.py.
    run_yosys(yosys, out, "elaborate", [
        "read_verilog -sv -D SYNTHESIS " + quote(normalized),
        "read_verilog -sv -noblackbox -D SYNTHESIS " + quote(RAM_SOURCE),
        "hierarchy -top student_top", "proc", "memory_collect",
        "write_json " + quote(out / "elaborated.json"),
    ])
    prepared, wrappers, ram_libs, macros = prepare_memories(
        json.loads((out / "elaborated.json").read_text()), out / "ram")
    library_reads = ["read_liberty -lib -ignore_miss_func " + quote(p)
                     for p in [*libs, *ram_libs]]
    library_args = " ".join("-liberty " + quote(p) for p in libs)
    all_library_args = " ".join("-liberty " + quote(p) for p in [*libs, *ram_libs])
    seq = next(p for p in libs if "_SEQ_" in p.name)
    commands = [
        "read_json " + quote(prepared), "read_verilog " + quote(wrappers),
        *library_reads, "hierarchy -check -top student_top",
        "synth -top student_top -noabc" + {"opt": " -flatten", "diagnose": " -hieropt", "hierarchy": ""}[args.mode],
        "check -assert", "select -assert-none a:init t:$dlatch* t:$_DLATCH*",
        "dfflibmap -liberty " + quote(seq),
        "abc -exe " + quote(abc) + " " + library_args + " -D 2000",
        "clean", "delete t:$scopeinfo", "clean -purge",
        "hilomap -hicell TIEHIx1_ASAP7_75t_R H -locell TIELOx1_ASAP7_75t_R L",
        "check -assert -mapped",
        "tee -o " + quote(out / "stat.json") + " stat -json " + all_library_args,
        "write_json " + quote(out / "design.json"),
        "write_verilog -noattr -noexpr " + quote(out / "mapped.v"),
        "design -reset", *library_reads,
        "read_verilog " + quote(out / "mapped.v"),
        "hierarchy -check -top student_top", "check -assert -mapped",
    ]
    if args.prepare_only:
        script = out / "synth.ys"
        script.write_text("\n".join(commands) + "\n")
        print(f"Prepared: {script}")
        return
    run_yosys(yosys, out, "synth", commands)
    area = area_report(json.loads((out / "design.json").read_text()),
                       json.loads((out / "stat.json").read_text()), macros)
    report = {"mode": args.mode, "area": area, "sram_model": MODEL,
              "timing_analyzed": False, "abc_target_period_ns": 2.0,
              "inputs": [identity(p) for p in [*sources, RAM_SOURCE]],
              "normalized_input": identity(normalized),
              "libraries": [identity(p) for p in [*libs, *ram_libs]],
              "tools": {"yosys": subprocess.check_output([yosys, "-V"], text=True).strip(),
                        "sv2v": subprocess.check_output([sv2v, "--version"], text=True).strip(),
                        "abc_sha256": identity(Path(abc))["sha256"]}}
    (out / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({key: value for key, value in area.items()
                      if key not in ("module_tree", "sram_instances")}, indent=2))
    print(f"Report: {out / 'report.json'}")


if __name__ == "__main__":
    main()
