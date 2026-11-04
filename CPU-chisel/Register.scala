package Register
import chisel3._
import Config.CPUConfig._
class Register extends Bundle {
  val value = UInt(32.W)
  val busy = Bool()
  val prod = UInt(robBits.W)
}
