package ROB
import chisel3._
import Op.Op
class ROB extends Bundle {
  val op = new Op
  val predictedPc = UInt(32.W)
  val executed = Bool()
  val complete = Bool()
  val value = UInt(32.W)
  val addr = UInt(32.W)
  val nextPc = UInt(32.W)
  val taken = Bool()
}
