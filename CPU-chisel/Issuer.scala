package Issuer
import chisel3._
import chisel3.util._
import Config.CPUConfig._
import Op._
import RS.RS
import ROB.ROB
import CDB.CDB
import Register.Register

/** In-order dispatch and same-group renaming. Tags are ROB slots, never PC values. */
class Issuer extends Module {
  val io = IO(new Bundle {
    val enable = Input(Bool())
    val available = Input(UInt(log2Ceil(issueWidth + 1).W))
    val ops = Input(Vec(issueWidth, new Op))
    val prediction = Input(Vec(issueWidth, Bool()))
    val regs = Input(Vec(32, new Register))
    val robs = Input(Vec(ROBWidth, new ROB))
    val cdbs = Input(Vec(cdbCount, new CDB))
    val tail = Input(UInt(robBits.W))
    val robSpace = Input(UInt(log2Ceil(ROBWidth + 1).W))
    val rsSpace = Input(UInt(log2Ceil(RSWidth + 1).W))
    val count = Output(UInt(log2Ceil(issueWidth + 1).W))
    val rs = Output(Vec(issueWidth, new RS))
    val rob = Output(Vec(issueWidth, new ROB))
    val control = Output(Bool())
    val nextPc = Output(UInt(32.W))
  })
  val stopped = Wire(Vec(issueWidth + 1, Bool()))
  val counts = Wire(Vec(issueWidth + 1, UInt(log2Ceil(issueWidth + 1).W)))
  stopped(0) := !io.enable
  counts(0) := 0.U
  io.control := false.B
  io.nextPc := 0.U
  for (i <- 0 until issueWidth) {
    val op = io.ops(i)
    val rs = io.rs(i)
    rs := 0.U.asTypeOf(new RS)
    rs.op := op
    rs.id := io.tail + i.U
    // Base values include retirement bypass supplied by CPU.
    def operand(index: UInt, uses: Bool, value: UInt, tag: UInt, ready: Bool): Unit = {
      value := Mux(index === 0.U, 0.U, io.regs(index).value)
      tag := io.regs(index).prod
      ready := !uses || index === 0.U || !io.regs(index).busy
      when(uses && index =/= 0.U && io.regs(index).busy) {
        when(io.robs(tag).complete) { value := io.robs(tag).value; ready := true.B }
        for (b <- 0 until cdbCount) {
          when(io.cdbs(b).valid && io.cdbs(b).complete && io.cdbs(b).id === io.regs(index).prod) {
            value := io.cdbs(b).value; ready := true.B
          }
        }
      }
      // The youngest matching earlier lane wins, including WAW chains.
      for (j <- 0 until i) {
        when(uses && index =/= 0.U && io.ops(j).writesRd && io.ops(j).rd === index) {
          tag := io.tail + j.U; ready := false.B
        }
      }
    }
    operand(op.r1, op.uses1, rs.v1, rs.q1, rs.ready1)
    operand(op.r2, op.uses2, rs.v2, rs.q2, rs.ready2)
    val predicted = Mux(op.type_ === OP_TYPE.OP_JAL || (op.branch && io.prediction(i)),
      op.pc + op.imm, op.pc + 4.U)
    io.rob(i) := 0.U.asTypeOf(new ROB)
    io.rob(i).op := op
    io.rob(i).predictedPc := predicted
    counts(i+1) := counts(i)
    stopped(i+1) := stopped(i)
    when(!stopped(i) && i.U < io.available && i.U < io.robSpace && i.U < io.rsSpace) {
      counts(i+1) := (i+1).U
      when(op.branch || op.jump) {
        stopped(i+1) := true.B
        io.control := true.B
        io.nextPc := predicted
      }
    }.otherwise { stopped(i+1) := true.B }
  }
  io.count := counts(issueWidth)
}
