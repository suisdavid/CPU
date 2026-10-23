//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused
package cpu2026

import CPU._
import chisel3._

/** Course interface. Replace the idle outputs with your CPU implementation. */
class StudentTop extends Module with RequireSyncReset {
  override def desiredName = "student_top"

  // Separate IO declarations preserve the exact harness port names (no io_ prefix).
  val araddr  = IO(Output(UInt(32.W)))
  val arvalid = IO(Output(Bool()))
  val arready = IO(Input(Bool()))

  val rdata   = IO(Input(UInt(32.W)))
  val rresp   = IO(Input(UInt(2.W)))
  val rvalid  = IO(Input(Bool()))
  val rready  = IO(Output(Bool()))

  val awaddr  = IO(Output(UInt(32.W)))
  val awvalid = IO(Output(Bool()))
  val awready = IO(Input(Bool()))

  val wdata   = IO(Output(UInt(32.W)))
  val wstrb   = IO(Output(UInt(4.W)))
  val wvalid  = IO(Output(Bool()))
  val wready  = IO(Input(Bool()))

  val bresp   = IO(Input(UInt(2.W)))
  val bvalid  = IO(Input(Bool()))
  val bready  = IO(Output(Bool()))

  // This skeleton builds but does not execute instructions or pass CPU tests.
  val cpu=Module(new CPU)
  araddr  := cpu.io.araddr
  arvalid := cpu.io.arvalid
  rready  := cpu.io.rready
  awaddr  := cpu.io.awaddr
  awvalid := cpu.io.awvalid
  wdata   := cpu.io.wdata
  wstrb   := cpu.io.wstrb
  wvalid  := cpu.io.wvalid
  bready  := cpu.io.bready
  cpu.io.arready := arready
  cpu.io.rdata   := rdata
  cpu.io.rresp   := rresp
  cpu.io.rvalid  := rvalid
  cpu.io.awready := awready
  cpu.io.wready  := wready
  cpu.io.bresp   := bresp
  cpu.io.bvalid  := bvalid

}