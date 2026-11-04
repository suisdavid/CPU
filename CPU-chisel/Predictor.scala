package Predictor
import chisel3._
import chisel3.util._
import Config.CPUConfig._

/** Retirement-trained two-bit BHT. Predictions are advisory; ROB checks the actual next PC. */
class Predictor extends Module {
  val io = IO(new Bundle {
    val pc = Input(Vec(issueWidth, UInt(32.W)))
    val taken = Output(Vec(issueWidth, Bool()))
    val update = Input(Bool())
    val updatePc = Input(UInt(32.W))
    val updateTaken = Input(Bool())
  })
  val counters = RegInit(VecInit(Seq.fill(1 << PBIT)(1.U(2.W))))
  for(i <- 0 until issueWidth) { io.taken(i) := counters(io.pc(i)(PBIT+1,2))(1) }
  val index = io.updatePc(PBIT+1,2)
  when(io.update) {
    when(io.updateTaken && counters(index) =/= 3.U) { counters(index) := counters(index) + 1.U }
    when(!io.updateTaken && counters(index) =/= 0.U) { counters(index) := counters(index) - 1.U }
  }
}
