
//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused

package ROB
import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import chisel3.util._
import _root_.circt.stage.ChiselStage

class ROB extends Bundle{
    val value = UInt(32.W)
    val dest = UInt(32.W)
    val type_ = UInt(32.W)//if store memory: type=1/2/4; if branch: type<0;otherwise type=0
    val ready = Bool()
}