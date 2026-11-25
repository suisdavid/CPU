package cpu2026

import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import Committer.Committer
import Config.CPUConfig._

object CommitterTests extends App {
  case class Entry(id: Int, dest: Int = 1, value: Int = 0,
                   kind: Int = 0, ready: Boolean = true)
  test(new Committer){ d =>
    def idle(): Unit = {
      d.io.valid_num.poke(0.U)
      d.io.clear_valid.poke(false.B)
      d.io.store_received.poke(false.B)
      d.io.cdbs.foreach{c=>c.valid.poke(false.B);c.id.poke(0.U);c.dest.poke(0.S);c.value.poke(0.U)}
      d.io.in_robs.foreach{r=>r.id.poke(0.U);r.dest.poke(0.S);r.value.poke(0.U);r.type_.poke(0.S);r.ready.poke(false.B)}
    }
    def clear(): Unit = {
      idle();d.io.clear_valid.poke(true.B)
      d.io.out_commit_num.expect(0.U);d.io.out_store_valid.expect(false.B)
      d.io.out_predictor_valid.expect(false.B);d.io.out_flush.expect(false.B)
      d.io.out_reg_valid.foreach(_.expect(false.B))
      d.clock.step();idle();d.io.out_space.expect((ROBWidth-1).U)
    }
    def enqueue(es: Entry*): Unit = {
      d.io.valid_num.poke(es.size.U)
      es.zipWithIndex.foreach{case(e,i)=>
        val r=d.io.in_robs(i)
        r.id.poke(e.id.U);r.dest.poke(e.dest.S);r.value.poke(e.value.U)
        r.type_.poke(e.kind.S);r.ready.poke(e.ready.B)
      }
    }
    def wb(lane: Int, e: Entry): Unit = {
      d.io.out_reg_valid(lane).expect((e.dest!=0).B)
      d.io.out_reg_id(lane).expect(e.dest.U)
      d.io.out_reg_prod(lane).expect(e.id.U)
      d.io.out_reg_val(lane).expect(e.value.U)
    }
    clear()
    // Full-width retirement plus simultaneous full-width enqueue, through wrap.
    var group=(0 until issueWidth).map(i=>Entry(i+1,5,100+i))
    enqueue(group:_*);d.clock.step();idle()
    for(round<-0 until 40){
      d.io.out_commit_num.expect(issueWidth.U)
      group.zipWithIndex.foreach{case(e,i)=>wb(i,e)}
      d.io.out_l.expect(((round*issueWidth)%ROBWidth).U)
      val next=(0 until issueWidth).map(i=>Entry((round+1)*issueWidth+i+1,5,200+round*issueWidth+i))
      if(round<39)enqueue(next:_*)
      d.clock.step();idle();group=next
    }
    d.io.out_commit_num.expect(0.U);d.io.out_l.expect(d.io.out_r.peek())

    // Start a full group at slot 15, so the lane indices cross 15 -> 0.
    clear()
    for(n<-0 until ROBWidth-1){
      enqueue(Entry(n+1));d.clock.step();idle()
      d.io.out_commit_num.expect(1.U);d.clock.step()
    }
    d.io.out_l.expect((ROBWidth-1).U)
    val wrapped=(0 until issueWidth).map(i=>Entry(100+i,6,100+i))
    enqueue(wrapped:_*);d.clock.step();idle();d.io.out_commit_num.expect(issueWidth.U)
    wrapped.zipWithIndex.foreach{case(e,i)=>wb(i,e)}
    d.clock.step();d.io.out_l.expect((issueWidth-1).U);d.io.out_commit_num.expect(0.U)

    // A ready younger instruction cannot pass a hole; CDB completion is retained.
    clear();enqueue(Entry(1,value=11),Entry(2,ready=false),Entry(3,value=33),Entry(4,value=44))
    d.clock.step();idle();d.io.out_commit_num.expect(1.U)
    d.clock.step();d.io.out_commit_num.expect(0.U)
    d.io.cdbs(0).valid.poke(true.B);d.io.cdbs(0).id.poke(2.U)
    d.io.cdbs(0).dest.poke(7.S);d.io.cdbs(0).value.poke(22.U)
    d.clock.step();idle();d.io.out_commit_num.expect(3.U)
    wb(0,Entry(2,7,22));d.clock.step();d.io.out_commit_num.expect(0.U)

    // An older register can retire before a blocked store; payload stays stable.
    clear();enqueue(Entry(1,value=11),Entry(2,0x100,0x55,1),Entry(3,value=33),Entry(4,0x200,0x66,4))
    d.clock.step();idle();d.io.out_commit_num.expect(1.U)
    d.io.out_store_valid.expect(true.B);d.io.out_store_mask.expect(1.U)
    d.clock.step()
    for(_<-0 until 4){
      d.io.out_commit_num.expect(0.U);d.io.out_store_valid.expect(true.B)
      d.io.out_store_addr.expect(0x100.U);d.io.out_store_value.expect(0x55.U)
      d.io.out_reg_valid.foreach(_.expect(false.B));d.clock.step()
    }
    d.io.store_received.poke(true.B);d.io.out_commit_num.expect(1.U)
    d.clock.step();idle();d.io.out_commit_num.expect(1.U);wb(0,Entry(3,value=33))
    d.io.out_store_addr.expect(0x200.U);d.io.out_store_mask.expect(15.U)
    d.io.store_received.poke(true.B);d.io.out_commit_num.expect(2.U)
    d.clock.step();idle();d.io.out_commit_num.expect(0.U)

    // Two ready branches use the existing single predictor update port in order.
    clear();enqueue(Entry(1,0,0,-0x101),Entry(2,value=22),Entry(3,0,0,-0x105),Entry(4,value=44))
    d.clock.step();idle();d.io.out_commit_num.expect(2.U)
    d.io.out_predictor_valid.expect(true.B);d.io.out_predictor_addr.expect(0x100.U)
    wb(1,Entry(2,value=22));d.io.out_reg_valid(3).expect(false.B)
    d.clock.step();d.io.out_commit_num.expect(2.U);d.io.out_predictor_addr.expect(0x104.U)
    wb(1,Entry(4,value=44));d.clock.step()

    // Mispredict at every lane: commit the branch, suppress all younger effects.
    for(branchLane<-0 until issueWidth){
      clear()
      val es=(0 until issueWidth).map(i=>
        if(i==branchLane) Entry(10+i,0x400,1,-0x101)
        else if(i>branchLane) Entry(10+i,0x800,123,4)
        else Entry(10+i,5,10+i))
      enqueue(es:_*);d.clock.step();idle()
      d.io.out_commit_num.expect((branchLane+1).U)
      d.io.out_flush.expect(true.B);d.io.out_flushval.expect((10+branchLane).U)
      d.io.out_newaddr.expect(0x400.U);d.io.out_store_valid.expect(false.B)
      for(i<-branchLane until issueWidth)d.io.out_reg_valid(i).expect(false.B)
      d.clock.step();clear()
    }
    // Halt and x0 still occupy retirement slots but cannot update younger state.
    clear();enqueue(Entry(1,0,123),Entry(2,-1),Entry(3,value=33))
    d.clock.step();idle();d.io.out_commit_num.expect(2.U)
    d.io.out_halt.expect(true.B);d.io.out_reg_valid.foreach(_.expect(false.B))
    clear()
  }
  println(s"PASS Committer: $issueWidth-wide retirement, 40 wrap rounds, CDB holes, store ACK/backpressure, branch ordering/flush, halt and x0")
}
