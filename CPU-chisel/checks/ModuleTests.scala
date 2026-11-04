package cpu2026
import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import Config.CPUConfig._
import Op._
import RS.RS
import CDB.CDB
import Executer._

object ModuleTests {
  val mask = (BigInt(1) << 32) - 1
  def unsigned(x: BigInt): BigInt = x & mask
  def signed(x: BigInt): BigInt = if(x.testBit(31)) x - (BigInt(1) << 32) else x
  def init(r: RS): Unit = {
    r.id.poke(0.U); r.v1.poke(0.U); r.v2.poke(0.U); r.q1.poke(0.U); r.q2.poke(0.U)
    r.ready1.poke(true.B); r.ready2.poke(true.B)
    val o = r.op
    o.type_.poke(OP_TYPE.OP_NONE)
    Seq(o.rd,o.r1,o.r2,o.imm,o.pc,o.instruction).foreach(_.poke(0.U))
    Seq(o.uses1,o.uses2,o.writesRd,o.load,o.store,o.branch,o.jump,o.mul,o.div,o.illegal).foreach(_.poke(false.B))
  }
  def clear(c: CDB): Unit = {
    c.valid.poke(false.B); c.complete.poke(false.B); c.taken.poke(false.B)
    Seq(c.id,c.value,c.addr,c.nextPc).foreach(_.poke(0.U))
  }
  def main(args: Array[String]): Unit = {
    val random = new scala.util.Random(20261103L)
    val edges = Seq(0L,1L,2L,0x7fffffffL,0x80000000L,0x80000001L,0xfffffffeL,0xffffffffL).map(BigInt(_))
    val pairs = for(a <- edges; b <- edges) yield (a,b)
    val samples = pairs ++ Seq.fill(160)((BigInt(32,random),BigInt(32,random)))
    test(new Mult) { d =>
      init(d.io.in.bits); d.io.flush.poke(false.B); d.io.in.valid.poke(false.B)
      for((a,b) <- samples; op <- Seq(OP_TYPE.OP_MUL,OP_TYPE.OP_MULH,OP_TYPE.OP_MULHSU,OP_TYPE.OP_MULHU)) {
        d.io.in.valid.poke(true.B); d.io.in.bits.v1.poke(a.U); d.io.in.bits.v2.poke(b.U); d.io.in.bits.op.type_.poke(op)
        val value = if(op == OP_TYPE.OP_MUL) a*b else {
          val aa = if(op == OP_TYPE.OP_MULHU) a else signed(a)
          val bb = if(op == OP_TYPE.OP_MULH) signed(b) else b
          (aa*bb) >> 32
        }
        d.clock.step(); d.io.out.valid.expect(true.B); d.io.out.value.expect(unsigned(value).U)
      }
      d.io.flush.poke(true.B); d.io.out.valid.expect(false.B); d.clock.step()
      d.io.in.valid.poke(false.B); d.io.flush.poke(false.B); d.io.out.valid.expect(false.B)
    }
    println(s"PASS multiplier: ${samples.size*4} vectors and flush")
    test(new Divider) { d =>
      init(d.io.in.bits); d.io.flush.poke(false.B); d.io.in.valid.poke(false.B)
      for((a,b) <- samples; op <- Seq(OP_TYPE.OP_DIV,OP_TYPE.OP_DIVU,OP_TYPE.OP_REM,OP_TYPE.OP_REMU)) {
        val sign = op == OP_TYPE.OP_DIV || op == OP_TYPE.OP_REM
        val rem = op == OP_TYPE.OP_REM || op == OP_TYPE.OP_REMU
        val aa = if(sign) signed(a) else a
        val bb = if(sign) signed(b) else b
        val expected = if(bb == 0) { if(rem) a else mask } else if(rem) aa%bb else aa/bb
        d.io.in.ready.expect(true.B); d.io.in.valid.poke(true.B)
        d.io.in.bits.v1.poke(a.U); d.io.in.bits.v2.poke(b.U); d.io.in.bits.op.type_.poke(op)
        d.clock.step(); d.io.in.valid.poke(false.B)
        d.clock.step(31); d.io.out.valid.expect(true.B); d.io.out.value.expect(unsigned(expected).U)
        d.clock.step(); d.io.out.valid.expect(false.B)
      }
      d.io.in.valid.poke(true.B); d.clock.step(); d.io.in.valid.poke(false.B); d.clock.step(5)
      d.io.flush.poke(true.B); d.clock.step(); d.io.flush.poke(false.B)
      for(_ <- 0 until 40) { d.io.out.valid.expect(false.B); d.clock.step() }
    }
    println(s"PASS divider: ${samples.size*4} vectors and cancellation")
    test(new Executer) { d =>
      d.io.count.poke(0.U); d.io.flush.poke(false.B)
      d.io.in.foreach(init); d.io.cdbs.foreach(clear)
      // Older add waits on producer 7; younger independent add must execute first.
      d.io.in(0).op.type_.poke(OP_TYPE.OP_ADDI); d.io.in(0).op.imm.poke(3.U)
      d.io.in(0).ready1.poke(false.B); d.io.in(0).q1.poke(7.U)
      d.io.count.poke(1.U); d.clock.step(); d.io.count.poke(0.U)
      d.io.in(0).id.poke(1.U); d.io.in(0).ready1.poke(true.B); d.io.in(0).op.imm.poke(42.U)
      d.io.count.poke(1.U); d.clock.step(); d.io.count.poke(0.U)
      d.io.out(0).valid.expect(true.B); d.io.out(0).id.expect(1.U); d.io.out(0).value.expect(42.U)
      d.clock.step(); d.io.out.foreach(_.valid.expect(false.B))
      d.io.cdbs(0).valid.poke(true.B); d.io.cdbs(0).complete.poke(true.B)
      d.io.cdbs(0).id.poke(7.U); d.io.cdbs(0).value.poke(5.U)
      d.clock.step(); clear(d.io.cdbs(0))
      d.io.out(0).valid.expect(true.B); d.io.out(0).id.expect(0.U); d.io.out(0).value.expect(8.U)
      d.clock.step(); d.io.space.expect(RSWidth.U)
    }
    println("PASS reservation station: younger-ready-first execution and CDB wakeup")
  }
}
