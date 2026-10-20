//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused

package RS
import Op._
import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import chisel3.util._
import _root_.circt.stage.ChiselStage

class RS extends Bundle{
    val type_ = OP_TYPE()
    val v1 = UInt(32.W)
    val v2 = UInt(32.W)
    val q1 = SInt(32.W)
    val q2 = SInt(32.W)
    val dest = SInt(32.W)
    val id = SInt(32.W)
    val is_halt = Bool()
}