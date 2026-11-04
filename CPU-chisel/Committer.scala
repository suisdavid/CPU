package Committer
import chisel3._
import chisel3.util._
import Config.CPUConfig._
import ROB.ROB
import CDB.CDB

class Committer extends Module {
  val io = IO(new Bundle {
    val count = Input(UInt(log2Ceil(issueWidth + 1).W))
    val in = Input(Vec(issueWidth, new ROB))
    val cdbs = Input(Vec(cdbCount, new CDB))
    val retire = Input(Bool())
    val flush = Input(Bool())
    val head = Output(new ROB)
    val headValid = Output(Bool())
    val headId = Output(UInt(robBits.W))
    val tail = Output(UInt(robBits.W))
    val space = Output(UInt(log2Ceil(ROBWidth + 1).W))
    val entries = Output(Vec(ROBWidth, new ROB))
  })
  val entries = RegInit(0.U.asTypeOf(Vec(ROBWidth, new ROB)))
  val head = RegInit(0.U(robBits.W))
  val tail = RegInit(0.U(robBits.W))
  val used = RegInit(0.U(log2Ceil(ROBWidth + 1).W))
  io.head := entries(head)
  io.headValid := used =/= 0.U
  io.headId := head
  io.tail := tail
  io.space := ROBWidth.U - used
  io.entries := entries
  assert(used <= ROBWidth.U)
  assert(io.count <= io.space)
  when(io.flush) {
    used := 0.U; head := 0.U; tail := 0.U
    entries := 0.U.asTypeOf(entries)
  }.otherwise {
    for (b <- 0 until cdbCount) {
      val c = io.cdbs(b)
      when(c.valid) {
        entries(c.id).executed := true.B
        entries(c.id).complete := c.complete
        entries(c.id).value := c.value
        entries(c.id).addr := c.addr
        entries(c.id).nextPc := c.nextPc
        entries(c.id).taken := c.taken
      }
    }
    for (i <- 0 until issueWidth) {
      val slot = (tail + i.U)(robBits-1,0)
      when(i.U < io.count) { entries(slot) := io.in(i) }
    }
    tail := tail + io.count
    used := used + io.count - io.retire.asUInt
    when(io.retire) { assert(io.headValid && io.head.complete); head := head + 1.U }
  }
}
