#!/usr/bin/env python3
"""Self-checking RV32IM programs. No assembler required; runs real generated RTL.

Usage: python3 tests/regression.py --sim build/sim [--seeds 20]
For randomized independent AXI stalls use --sim build-stress/sim.
"""
import argparse
import random
import re
import subprocess
from pathlib import Path
MASK = 0xffffffff
SUCCESS = 0x5a17c0de

def s(x): return (x & MASK) - (0x100000000 if x & 0x80000000 else 0)
def i(op, rd, f3, r1, imm): return ((imm & 4095) << 20) | r1 << 15 | f3 << 12 | rd << 7 | op
def r(rd, f3, a, b, f7=0): return f7 << 25 | b << 20 | a << 15 | f3 << 12 | rd << 7 | 0x33
def st(f3, base, value, imm=0):
    imm &= 4095
    return (imm >> 5) << 25 | value << 20 | base << 15 | f3 << 12 | (imm & 31) << 7 | 0x23
def branch(f3, a, b, off):
    assert off % 2 == 0 and -4096 <= off < 4096
    off &= 8191
    return (off >> 12) << 31 | ((off >> 5) & 63) << 25 | b << 20 | a << 15 | f3 << 12 | ((off >> 1) & 15) << 8 | ((off >> 11) & 1) << 7 | 0x63
def jal(rd, off):
    assert off % 2 == 0 and -(1 << 20) <= off < (1 << 20)
    off &= (1 << 21) - 1
    return (off >> 20) << 31 | ((off >> 1) & 1023) << 21 | ((off >> 11) & 1) << 20 | ((off >> 12) & 255) << 12 | rd << 7 | 0x6f

def arithmetic(f3, f7, a, b):
    if f7 == 1:
        if f3 == 0: return a*b & MASK
        if f3 == 1: return (s(a)*s(b) >> 32) & MASK
        if f3 == 2: return (s(a)*b >> 32) & MASK
        if f3 == 3: return (a*b >> 32) & MASK
        sign = f3 in (4, 6)
        aa, bb = (s(a), s(b)) if sign else (a, b)
        if bb == 0: return a if f3 in (6, 7) else MASK
        q = abs(aa)//abs(bb) * (-1 if (aa < 0) != (bb < 0) else 1)
        return (aa-q*bb if f3 in (6, 7) else q) & MASK
    return [lambda: a-b if f7 == 32 else a+b, lambda: a << (b & 31),
            lambda: int(s(a)<s(b)), lambda: int(a<b), lambda: a^b,
            lambda: s(a) >> (b&31) if f7 == 32 else a >> (b&31),
            lambda: a|b, lambda: a&b][f3]() & MASK

class Program:
    def __init__(self): self.code=[]; self.checks=0
    @property
    def pc(self): return len(self.code)*4
    def emit(self, *xs): self.code.extend(x & MASK for x in xs)
    def li(self, rd, value):
        value &= MASK
        hi = ((value+2048) >> 12) & 0xfffff
        self.emit(hi << 12 | rd << 7 | 0x37, i(0x13,rd,0,rd,value & 4095))
    def check(self, reg, value):
        self.checks += 1
        self.li(27, value)
        self.emit(branch(0,reg,27,16), i(0x13,30,0,0,self.checks), 0x80000fb7, st(2,31,30))
    def finish(self):
        self.li(30,SUCCESS); self.emit(0x80000fb7, st(2,31,30), jal(0,0))
        data = b''.join(x.to_bytes(4,'little') for x in self.code)
        return 'CPU2026-OJ 1\n1000000 10\n@00000000\n'+'\n'.join(data[n:n+16].hex(' ') for n in range(0,len(data),16))+'\n'

def directed():
    p=Program()
    edges=[0,1,31,32,0x7fffffff,0x80000000,0x80000001,0xfffffffe,0xffffffff]
    # All M operations, including division by zero and signed overflow.
    for a in edges:
        for b in edges:
            p.li(5,a); p.li(6,b)
            for f3 in range(8):
                p.emit(r(10,f3,5,6,1)); p.check(10,arithmetic(f3,1,a,b))
    for a,b in [(x,y) for x in edges for y in [0,1,31,32,0xffffffff]]:
        p.li(5,a); p.li(6,b)
        for f3,f7 in [(f,0) for f in range(8)]+[(0,32),(5,32)]:
            p.emit(r(10,f3,5,6,f7)); p.check(10,arithmetic(f3,f7,a,b))
    for immediate in [-2048,-1,0,1,2047]:
        a=0x80001234; p.li(5,a)
        for f3 in [0,2,3,4,6,7]:
            p.emit(i(0x13,10,f3,5,immediate)); p.check(10,arithmetic(f3,0,a,immediate&MASK))
    for a in edges:
        p.li(5,a)
        for shift in [0,1,16,31]:
            for f3,high in [(1,0),(5,0),(5,1024)]:
                p.emit(i(0x13,10,f3,5,high+shift))
                p.check(10,arithmetic(f3,32 if high else 0,a,shift))
    p.emit(i(0x13,0,0,0,123)); p.check(0,0)
    # 0x0ff00513 is an ordinary ADDI in the 2026 harness, never a halt.
    p.emit(i(0x13,10,0,0,255)); p.check(10,255)
    pc=p.pc; p.emit(0x12345000 | 10 << 7 | 0x17); p.check(10,pc+0x12345000)
    # JAL writes PC+4 and redirects, skipping a wrong-path MMIO store.
    p.li(31,0x80000000)
    pc=p.pc; p.emit(jal(10,12),st(2,31,0),i(0x13,10,0,0,0)); p.check(10,pc+4)
    # JALR rd == rs1 must read the OLD value, and clear target bit zero.
    target=p.pc+28; p.li(10,target+5)
    pc=p.pc; p.emit(i(0x67,10,0,10,-4),st(2,31,0),i(0x13,10,0,0,0),jal(0,0),jal(0,0)); p.check(10,pc+4)
    # Both outcomes for every conditional branch, including sign boundaries.
    for a,b in [(0,0),(1,2),(0x80000000,1),(0xffffffff,0),(0x7fffffff,0x80000000)]:
        p.li(5,a); p.li(6,b)
        outcomes={0:a==b,1:a!=b,4:s(a)<s(b),5:s(a)>=s(b),6:a<b,7:a>=b}
        for f3,taken in outcomes.items():
            p.emit(i(0x13,10,0,0,0),branch(f3,5,6,12),i(0x13,10,0,0,1),jal(0,8),i(0x13,10,0,0,2))
            p.check(10,2 if taken else 1)
    # Backward branch, WAW, and ROB wrap many times.
    p.li(5,130); p.li(10,0)
    loop=p.pc; p.emit(i(0x13,10,0,10,1),i(0x13,5,0,5,-1)); p.emit(branch(1,5,0,loop-p.pc)); p.check(10,130)
    # Byte/halfword masks on cold misses, hits and conflicting replacements.
    base=0x100000
    for k in range(10):
        p.li(20,base+k*512); p.li(5,0x12345678+k); p.emit(st(2,20,5))
    for k in range(10):
        p.li(20,base+k*512); p.emit(i(3,10,2,20,0)); p.check(10,0x12345678+k)
        value=0x12345678+k
        for offset in range(4):
            byte=(0x80+offset*17+k)&255
            p.li(5,byte); p.emit(st(0,20,5,offset)); value=(value & ~(255<<(8*offset))) | byte<<(8*offset)
            p.emit(i(3,10,0,20,offset)); p.check(10,(byte-256)&MASK)
            p.emit(i(3,10,4,20,offset)); p.check(10,byte)
            p.emit(i(3,10,2,20,0)); p.check(10,value)
        for offset in [0,2]:
            p.li(5,0x8001+k); p.emit(st(1,20,5,offset))
            p.emit(i(3,10,1,20,offset)); p.check(10,(0x8001+k-65536)&MASK)
            p.emit(i(3,10,5,20,offset)); p.check(10,0x8001+k)
        # Negative load offset.
        p.emit(i(0x13,20,0,20,4),i(3,10,5,20,-2)); p.check(10,0x8001+k)
    return p

def random_program(seed):
    rng=random.Random(seed); p=Program(); regs=[0]*32
    for rd in range(1,26):
        regs[rd]=rng.getrandbits(32); p.li(rd,regs[rd])
    for n in range(600):
        rd=rng.randrange(26); a=rng.randrange(26); b=rng.randrange(26)
        if rng.randrange(3):
            f3=rng.randrange(8); f7=rng.choice([0,1,1])
            if f7==0 and f3 in [0,5] and rng.randrange(2): f7=32
            p.emit(r(rd,f3,a,b,f7)); val=arithmetic(f3,f7,regs[a],regs[b])
        else:
            imm=rng.randrange(-2048,2048); f3=rng.choice([0,2,3,4,6,7])
            p.emit(i(0x13,rd,f3,a,imm)); val=arithmetic(f3,0,regs[a],imm&MASK)
        if rd: regs[rd]=val
        if n%73==0:
            for reg in range(1,26): p.check(reg,regs[reg])
    for reg in range(26): p.check(reg,regs[reg])
    return p

def main():
    ap=argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--sim',type=Path,default=Path('build/sim'))
    ap.add_argument('--seeds',type=int,default=20)
    ap.add_argument('--stress',action='store_true',help='pass distinct AXI random seeds to the stress harness')
    args=ap.parse_args()
    cases=[('directed',directed())]+[(f'random-{seed}',random_program(seed)) for seed in range(args.seeds)]
    checks=0
    for bus_seed,(name,p) in enumerate(cases,1):
        image=p.finish()
        command=[str(args.sim.resolve())] + ([str(bus_seed)] if args.stress else [])
        run=subprocess.run(command,input=image,text=True,capture_output=True)
        if run.returncode or run.stdout.strip()!=str(SUCCESS):
            out=Path('build/regression-failure'); out.mkdir(parents=True,exist_ok=True)
            (out/f'{name}.in').write_text(image)
            raise SystemExit(f'{name} FAILED: output={run.stdout!r}\n{run.stderr}\nReproducer: {out/name}.in')
        checks+=p.checks
        match=re.search(r'CPU2026 cycles=(\d+)',run.stderr)
        print(f'PASS {name}: {p.checks} checks, cycles={match.group(1) if match else "?"}',flush=True)
    print(f'{len(cases)} programs passed; {checks} register/memory checks')
if __name__=='__main__': main()
