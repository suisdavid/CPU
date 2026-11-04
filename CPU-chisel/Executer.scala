package Executer
import chisel3._
import chisel3.util._
import Config.CPUConfig._
import Op._
import RS.RS
import CDB.CDB

/** Integer, branch and address-generation lane. Memory side effects occur only at ROB head. */
class ALU extends Module {
  val io = IO(new Bundle { val in = Input(new RS); val out = Output(new CDB) })
  val r = io.in
  val o = r.op
  val a = r.v1
  val b = Mux(o.uses2, r.v2, o.imm)
  val result = WireDefault(0.U(32.W))
  val taken = WireDefault(false.B)
  switch(o.type_) {
    is(OP_TYPE.OP_ADD, OP_TYPE.OP_ADDI) { result := a + b }
    is(OP_TYPE.OP_SUB) { result := a - b }
    is(OP_TYPE.OP_AND, OP_TYPE.OP_ANDI) { result := a & b }
    is(OP_TYPE.OP_OR, OP_TYPE.OP_ORI) { result := a | b }
    is(OP_TYPE.OP_XOR, OP_TYPE.OP_XORI) { result := a ^ b }
    is(OP_TYPE.OP_SLL, OP_TYPE.OP_SLLI) { result := a << b(4,0) }
    is(OP_TYPE.OP_SRL, OP_TYPE.OP_SRLI) { result := a >> b(4,0) }
    is(OP_TYPE.OP_SRA, OP_TYPE.OP_SRAI) { result := (a.asSInt >> b(4,0)).asUInt }
    is(OP_TYPE.OP_SLT, OP_TYPE.OP_SLTI) { result := (a.asSInt < b.asSInt).asUInt }
    is(OP_TYPE.OP_SLTU, OP_TYPE.OP_SLTIU) { result := (a < b).asUInt }
    is(OP_TYPE.OP_LUI) { result := o.imm }
    is(OP_TYPE.OP_AUIPC) { result := o.pc + o.imm }
    is(OP_TYPE.OP_JAL, OP_TYPE.OP_JALR) { result := o.pc + 4.U; taken := true.B }
    is(OP_TYPE.OP_BEQ) { taken := a === r.v2 }
    is(OP_TYPE.OP_BNE) { taken := a =/= r.v2 }
    is(OP_TYPE.OP_BLT) { taken := a.asSInt < r.v2.asSInt }
    is(OP_TYPE.OP_BGE) { taken := a.asSInt >= r.v2.asSInt }
    is(OP_TYPE.OP_BLTU) { taken := a < r.v2 }
    is(OP_TYPE.OP_BGEU) { taken := a >= r.v2 }
  }
  io.out := 0.U.asTypeOf(new CDB)
  io.out.id := r.id
  io.out.value := Mux(o.store, r.v2, result)
  io.out.addr := a + o.imm
  io.out.complete := !(o.load || o.store)
  io.out.taken := taken
  io.out.nextPc := Mux(taken, Mux(o.type_ === OP_TYPE.OP_JALR,
    (a + o.imm) & "hfffffffe".U, o.pc + o.imm), o.pc + 4.U)
}

/** Shared, fully pipelined multiplier. Sign-extend BEFORE multiplication, then slice. */
class Mult extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Valid(new RS))
    val flush = Input(Bool())
    val out = Output(new CDB)
  })
  val r = io.in.bits
  val signedA = r.op.type_ === OP_TYPE.OP_MULH || r.op.type_ === OP_TYPE.OP_MULHSU
  val signedB = r.op.type_ === OP_TYPE.OP_MULH
  val a = Cat(signedA && r.v1(31), r.v1).asSInt
  val b = Cat(signedB && r.v2(31), r.v2).asSInt
  val product = (a * b).asUInt
  val result = Reg(new CDB)
  val valid = RegNext(io.in.valid && !io.flush, false.B)
  when(io.in.valid) {
    result := 0.U.asTypeOf(new CDB)
    result.id := r.id
    result.value := Mux(r.op.type_ === OP_TYPE.OP_MUL, product(31,0), product(63,32))
    result.nextPc := r.op.pc + 4.U
    result.complete := true.B
  }
  io.out := result
  io.out.valid := valid && !io.flush
}

/** Shared 32-step restoring divider, using a 33-bit trial remainder. */
class Divider extends Module {
  val io = IO(new Bundle {
    val in = Flipped(Decoupled(new RS))
    val flush = Input(Bool())
    val out = Output(new CDB)
  })
  val busy = RegInit(false.B)
  val count = Reg(UInt(6.W))
  val quotient = Reg(UInt(32.W))
  val remainder = Reg(UInt(32.W))
  val divisor = Reg(UInt(32.W))
  val negQ = Reg(Bool())
  val negR = Reg(Bool())
  val wantR = Reg(Bool())
  val zeroDivisor = Reg(Bool())
  val original = Reg(UInt(32.W))
  val id = Reg(UInt(robBits.W))
  val nextPc = Reg(UInt(32.W))
  io.in.ready := !busy && !io.flush
  val trial = Cat(remainder, quotient(31))
  val subtract = trial >= Cat(0.U(1.W), divisor)
  val nextR = Mux(subtract, trial - divisor, trial)(31,0)
  val nextQ = Cat(quotient(30,0), subtract)
  io.out := 0.U.asTypeOf(new CDB)
  io.out.valid := busy && count === 31.U && !io.flush
  io.out.id := id
  io.out.nextPc := nextPc
  io.out.complete := true.B
  io.out.value := Mux(zeroDivisor, Mux(wantR, original, "hffffffff".U),
    Mux(wantR, Mux(negR, -nextR, nextR), Mux(negQ, -nextQ, nextQ)))
  when(io.flush) { busy := false.B }
    .elsewhen(io.in.fire) {
      val r = io.in.bits
      val signed = r.op.type_ === OP_TYPE.OP_DIV || r.op.type_ === OP_TYPE.OP_REM
      busy := true.B; count := 0.U
      quotient := Mux(signed && r.v1(31), -r.v1, r.v1)
      divisor := Mux(signed && r.v2(31), -r.v2, r.v2)
      remainder := 0.U
      negQ := signed && (r.v1(31) ^ r.v2(31))
      negR := signed && r.v1(31)
      wantR := r.op.type_ === OP_TYPE.OP_REM || r.op.type_ === OP_TYPE.OP_REMU
      zeroDivisor := r.v2 === 0.U
      original := r.v1; id := r.id; nextPc := r.op.pc + 4.U
    }.elsewhen(busy) {
      quotient := nextQ; remainder := nextR; count := count + 1.U
      when(count === 31.U) { busy := false.B }
    }
}

/** Shared reservation station: scan all entries, so blocked older instructions do not block ready younger ones. */
class Executer extends Module {
  val io = IO(new Bundle {
    val count = Input(UInt(log2Ceil(issueWidth + 1).W))
    val in = Input(Vec(issueWidth, new RS))
    val cdbs = Input(Vec(cdbCount, new CDB))
    val flush = Input(Bool())
    val space = Output(UInt(log2Ceil(RSWidth + 1).W))
    val out = Output(Vec(ALU_NUM + 2, new CDB))
  })
  val valid = RegInit(VecInit(Seq.fill(RSWidth)(false.B)))
  val entries = Reg(Vec(RSWidth, new RS))
  io.space := RSWidth.U - PopCount(valid)
  assert(io.count <= io.space)
  val ready = VecInit((0 until RSWidth).map(i => valid(i) && entries(i).ready1 && entries(i).ready2))
  val selected = Wire(Vec(ALU_NUM + 1, UInt(RSWidth.W)))
  selected(0) := 0.U
  for(lane <- 0 until ALU_NUM) {
    val candidates = VecInit((0 until RSWidth).map(i => ready(i) && !selected(lane)(i) &&
      !entries(i).op.mul && !entries(i).op.div)).asUInt
    val grant = PriorityEncoderOH(candidates)
    selected(lane+1) := selected(lane) | grant
    val alu = Module(new ALU)
    alu.io.in := Mux1H(grant, entries)
    io.out(lane) := alu.io.out
    io.out(lane).valid := candidates.orR && !io.flush
    for(i <- 0 until RSWidth) { when(grant(i)) { valid(i) := false.B } }
  }
  val mult = Module(new Mult)
  val div = Module(new Divider)
  val mulCandidates = VecInit((0 until RSWidth).map(i => ready(i) && entries(i).op.mul)).asUInt
  val divCandidates = VecInit((0 until RSWidth).map(i => ready(i) && entries(i).op.div)).asUInt
  val mulGrant = PriorityEncoderOH(mulCandidates)
  val divGrant = PriorityEncoderOH(divCandidates)
  mult.io.in.valid := mulCandidates.orR && !io.flush
  mult.io.in.bits := Mux1H(mulGrant, entries)
  mult.io.flush := io.flush
  div.io.in.valid := divCandidates.orR && !io.flush
  div.io.in.bits := Mux1H(divGrant, entries)
  div.io.flush := io.flush
  io.out(ALU_NUM) := mult.io.out
  io.out(ALU_NUM+1) := div.io.out
  for(i <- 0 until RSWidth) {
    when(mulGrant(i) || (divGrant(i) && div.io.in.ready)) { valid(i) := false.B }
    for(b <- 0 until cdbCount) {
      val c = io.cdbs(b)
      when(c.valid && c.complete) {
        when(!entries(i).ready1 && entries(i).q1 === c.id) { entries(i).v1 := c.value; entries(i).ready1 := true.B }
        when(!entries(i).ready2 && entries(i).q2 === c.id) { entries(i).v2 := c.value; entries(i).ready2 := true.B }
      }
    }
  }
  // Allocate only entries that were free at cycle start: no issue/execute combinational loop.
  val allocated = Wire(Vec(issueWidth+1, UInt(RSWidth.W)))
  allocated(0) := valid.asUInt
  for(lane <- 0 until issueWidth) {
    val grant = PriorityEncoderOH(~allocated(lane))
    allocated(lane+1) := allocated(lane) | grant
    for(i <- 0 until RSWidth) {
      when(lane.U < io.count && grant(i)) { valid(i) := true.B; entries(i) := io.in(lane) }
    }
  }
  when(io.flush) { valid.foreach(_ := false.B) }
}
