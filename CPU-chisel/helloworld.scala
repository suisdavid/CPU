//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused

import chisel3._
import chiseltest._
import chiseltest.RawTester.test

class RegNextModule extends Module {
  val io = IO(new Bundle {
    val in  = Input(UInt(12.W))
    val out = Output(UInt(12.W))
  })
  io.out:=0.U
  io.out := RegNext(io.in + 1.U)
}

object Main {
  def main(args: Array[String]): Unit = {
    // 默认使用 Treadle，不使用 Verilator
    test(new RegNextModule) { dut =>
      for (i <- 0 until 100) {
        dut.io.in.poke(i.U)
        dut.clock.step(1)
        dut.io.out.expect((i + 1).U)

      }
    }

    println("SUCCESS!!")
  }
}