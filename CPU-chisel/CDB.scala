package CDB
import chisel3._
import Config.CPUConfig._
class CDB extends Bundle {
  val valid = Bool()
  val id = UInt(robBits.W)
  val value = UInt(32.W)
  val addr = UInt(32.W)
  val nextPc = UInt(32.W)
  val taken = Bool()
  // A memory address may be ready before the load/store has completed.
  val complete = Bool()
}
