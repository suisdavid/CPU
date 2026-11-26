#!/usr/bin/env python3
"""Build the independent randomized AXI slave against the submitted RTL."""
import argparse
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / 'scripts'))
from build import read_sources
from fakeram import with_ram_source


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--verilator', default='verilator')
    ap.add_argument('--filelist', type=Path, default=ROOT / 'verilog/filelist.f')
    ap.add_argument('--out', type=Path, default=ROOT / 'build-stress')
    ap.add_argument('--jobs', type=int, default=4)
    args = ap.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    sources = with_ram_source(read_sources(args.filelist))
    subprocess.run([args.verilator, '--cc', '--exe', '--build', '--assert',
                    '-Wall', '-Wno-fatal', '--top-module', 'student_top',
                    '--Mdir', str(out / 'obj'), '-o', str(out / 'sim'),
                    '-j', str(args.jobs), '-CFLAGS', '-std=c++17',
                    *map(str, sources), str(ROOT / 'tests/axi_stress.cpp')], check=True)
    print(out / 'sim')

if __name__ == '__main__': main()
