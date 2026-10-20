//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused

package CPU
import Op._
import CDB._
import RS._
import ROB._
import Decoder._
import EXecuter._
import Committer._
import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import chisel3.util._
import _root_.circt.stage.ChiselStage

class CPU extends Module{
    val io=IO(new Bundle{
        val valid = Input(Bool())
        val id = Input(SInt(32.W))
        val opcode = Input(UInt(32.W))
        val out_valid = Output(Bool())
        val out_id = Output(SInt(32.W))
        val out_op = Output(new Op())
    })
}