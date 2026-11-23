package cpu2026
import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import chiseltest.simulator.VerilatorBackendAnnotation
import Executer._
import Op.OP_TYPE

object ModuleTests extends App {
  val mask=(BigInt(1)<<32)-1
  val edge=Seq[BigInt](0,1,31,32,0x7fffffffL,0x80000000L,0xfffffffeL,0xffffffffL)
  val rng=new scala.util.Random(2026)
  val pairs=(for(a<-edge;b<-edge) yield(a,b)) ++ Seq.fill(160)((BigInt(32,rng),BigInt(32,rng)))
  def signed(x:BigInt)=if(x.testBit(31))x-(BigInt(1)<<32) else x
  test(new Adder){d=>pairs.foreach{case(a,b)=>d.io.a.poke(a.U);d.io.b.poke(b.U);d.io.sum.expect(((a+b)&mask).U);d.io.cout.expect((a+b>mask).B)}}
  test(new Subtracter){d=>pairs.foreach{case(a,b)=>d.io.a.poke(a.U);d.io.b.poke(b.U);d.io.c.expect(((a-b)&mask).U)}}
  test(new Comparer){d=>pairs.foreach{case(a,b)=>d.io.a.poke(a.U);d.io.b.poke(b.U);d.io.signed.poke(false.B);d.io.c.expect((a>=b).B);d.io.signed.poke(true.B);d.io.c.expect((signed(a)>=signed(b)).B)}}
  println(s"PASS add/sub/compare: ${pairs.size} operand pairs")
  val vectors=pairs.flatMap{case(a,b)=>Seq((a,b,false),(a,b,true))}
  test(new Mult){d=>
    d.io.valid.poke(0.U);d.io.clear_valid.poke(false.B)
    for(t<-0 until vectors.size+2){
      if(t>=2){val(a,b,hi)=vectors(t-2);d.io.ready.expect((t-1).U);d.io.c.expect(((if(hi)(a*b)>>32 else a*b)&mask).U);d.io.low_nonzero.expect(((a*b&mask)!=0).B)}
      if(t<vectors.size){val(a,b,hi)=vectors(t);d.io.a.poke(a.U);d.io.b.poke(b.U);d.io.op.poke(hi.B);d.io.valid.poke((t+1).U)}else d.io.valid.poke(0.U)
      d.clock.step()
    }
    d.io.clear_valid.poke(true.B);d.clock.step();d.io.ready.expect(0.U);d.io.received.expect(0.U)
  }
  println(s"PASS multiplier: ${vectors.size} consecutive requests and flush")
  test(new Divider,Seq(VerilatorBackendAnnotation)){d=>
    d.io.valid.poke(0.U);d.io.clear_valid.poke(false.B)
    for(t<-0 until vectors.size+32){
      if(t>=32){val(a,b,q)=vectors(t-32);val want=if(b==0){if(q)mask else a}else if(q)a/b else a%b;d.io.ready.expect((t-31).U);d.io.c.expect(want.U)}
      if(t<vectors.size){val(a,b,q)=vectors(t);d.io.a.poke(a.U);d.io.b.poke(b.U);d.io.op.poke(q.B);d.io.valid.poke((t+1).U)}else d.io.valid.poke(0.U)
      d.clock.step()
    }
    d.io.clear_valid.poke(true.B);d.clock.step();d.io.ready.expect(0.U);d.io.received.expect(0.U)
    d.io.clear_valid.poke(false.B);d.clock.step(32);d.io.ready.expect(0.U)
  }
  println(s"PASS divider: ${vectors.size} consecutive requests and flush")
  test(new ALU(0),Seq(VerilatorBackendAnnotation)){d=>
    d.io.valid_num.poke(0.U);d.io.clear_valid.poke(false.B);d.io.clear_id.poke(0.S)
    d.io.cdbs.foreach{c=>c.valid.poke(false.B);c.id.poke(0.U);c.dest.poke(0.S);c.value.poke(0.U)}
    d.aluIO.mult_valid.poke(0.U);d.aluIO.mult_res.poke(0.U);d.aluIO.mult_low_nonzero.poke(false.B);d.aluIO.mult_received.poke(0.U)
    d.aluIO.div_valid.poke(0.U);d.aluIO.div_res.poke(0.U);d.aluIO.div_received.poke(0.U)
    d.io.rs.foreach{r=>r.type_.poke(OP_TYPE.OP_ADD);r.q1.poke((-1).S);r.q2.poke((-1).S);r.v1.poke(0.U);r.v2.poke(0.U);r.id.poke(0.U);r.dest.poke(1.S);r.is_halt.poke(false.B)}
    for(k<-0 until 40){
      // A younger ready entry can execute while the older entry is waiting.
      d.io.valid_num.poke(2.U)
      d.io.rs(0).id.poke((10*k+1).U);d.io.rs(0).q1.poke((1000+k).S);d.io.rs(0).v2.poke(7.U)
      d.io.rs(1).id.poke((10*k+2).U);d.io.rs(1).v1.poke(11.U);d.io.rs(1).v2.poke(13.U)
      d.clock.step();d.io.valid_num.poke(0.U)
      d.io.out_cdb.valid.expect(true.B);d.io.out_cdb.id.expect((10*k+2).U);d.io.out_cdb.value.expect(24.U)
      d.clock.step()
      d.io.cdbs(0).valid.poke(true.B);d.io.cdbs(0).id.poke((1000+k).U);d.io.cdbs(0).value.poke(9.U)
      d.io.out_cdb.valid.expect(true.B);d.io.out_cdb.id.expect((10*k+1).U);d.io.out_cdb.value.expect(16.U)
      d.clock.step();d.io.cdbs(0).valid.poke(false.B);d.io.out_cdb.valid.expect(false.B)
    }
  }
  println("PASS reservation station: younger ready issue, wakeup, and 40 ring wrap rounds")
}
