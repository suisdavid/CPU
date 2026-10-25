//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused

package Config
import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import chisel3.util._
import _root_.circt.stage.ChiselStage

object CPUConfig {
  val issueWidth: Int = 4
  val ROBWidth: Int = 16
  val RSWidth: Int = 16
  val ALU_NUM: Int = 4
  val issuerBufferWidth: Int = 16
}