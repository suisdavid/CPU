//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused
package Adder32
import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import _root_.circt.stage.ChiselStage
class Adder32 extends Module {
  val io = IO(new Bundle {
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(32.W))
    val cin = Input(Bool())
    val sum = Output(UInt(32.W))
    val cout = Output(Bool())
  })
  val p= Wire(Vec(63,Bool()))
  val g= Wire(Vec(63,Bool()))
  val c= Wire(Vec(32,Bool()))
  for (i<-0 until 32){
    p(i):=io.a(i)^io.b(i)
    g(i):=io.a(i)&io.b(i)
  }
  var offset1=32
  var offset2=0
  for (w<-4 to 0 by -1){
    for (i<-0 until (1<<w)){
      p(i+offset1):=p(i*2+offset2)&p(i*2+offset2+1)
      g(i+offset1):=g(i*2+offset2+1)|(p(i*2+offset2+1)&g(i*2+offset2))
    }
    offset2=offset1
    offset1+=(1<<w)

  }
  var offset=62
  io.cout:=g(offset)|(p(offset)&io.cin)
  c(0):=io.cin
  for (w<-4 to 0 by -1){
    for (i<-(32-(1<<(w+1))) to 0 by -(1<<(w+1))){
      offset-=2
      c(i+(1<<w)):=g(offset)|(p(offset)&c(i))
    }
  }
  val pBits = p.asUInt
  val cBits = c.asUInt

  io.sum := pBits(31, 0) ^ cBits  
}

object Main {
  def main(args: Array[String]): Unit = {
    // 默认使用 Treadle，不使用 Verilator
    test(new Adder32) { dut =>
        val a = 1
        val b = 1
        val c = 1
        dut.io.a.poke(a.U)
        dut.io.b.poke(b.U)
        dut.io.cin.poke(c.U)
        dut.io.sum.expect((a+b+c).U)
        dut.io.cout.expect(0.U)
    }

    println("SUCCESS!!")
  }
}

object GenerateVerilog {
  def main(args: Array[String]): Unit = {
    ChiselStage.emitSystemVerilogFile(
      new Adder32,
      args = Array("--target-dir", "generated")
    )
    println("Generated!!")
    println(chisel3.getVerilogString(new Adder32))
  }
}