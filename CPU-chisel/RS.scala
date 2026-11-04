package RS
import chisel3._
import Config.CPUConfig._
import Op.Op
class RS extends Bundle {
  val op = new Op
  val id = UInt(robBits.W)
  val v1 = UInt(32.W)
  val v2 = UInt(32.W)
  val q1 = UInt(robBits.W)
  val q2 = UInt(robBits.W)
  val ready1 = Bool()
  val ready2 = Bool()
}
