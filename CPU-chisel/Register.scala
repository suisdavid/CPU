//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
// Unused imports in the draft are not compilation errors.

package Register
import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import chisel3.util._
import _root_.circt.stage.ChiselStage

class Register extends Bundle{
    val value = UInt(32.W)
    val prod = SInt(32.W)//initialized to -1
}

