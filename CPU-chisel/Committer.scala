//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused

package Committer

import Config._
import ROB._
import CDB._
import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import _root_.circt.stage.ChiselStage

class Committer extends Module{//needs to add branch predictor!
  val io = IO(new Bundle {
    val valid_num = Input(UInt(32.W))
    val in_robs = Input(Vec(issueWidth,new ROB))
    val cdbs = Input(Vec(ALU_NUM+2, new CDB))

    val clear_valid = Input(Bool())

    val store_received = Input(Bool())
    val out_store_valid = Output(Bool())
    val out_store_addr = Output(UInt(32.W))
    val out_store_value = Output(UInt(32.W))
    val out_store_mask = Output(UInt(4.W))
    
    val out_reg_valid = Output(Bool())
    val out_reg_id = Output(UInt(5.W))
    val out_reg_val = Output(UInt(32.W))
    val out_reg_prod = Output(UInt(32.W))

    val out_flush = Output(Bool())
    val out_flushval = Output(UInt(32.W))
    val out_newaddr = Output(UInt(32.W))
    val out_l = Output(UInt(4.W))
    val out_r = Output(UInt(4.W))
    val out_space = Output(UInt(32.W))
    val out_robs= Output(Vec(ROBWidth,new ROB))

    val out_halt=Output(Bool())
  })
  val l = RegInit(0.U(4.W))
  val r = RegInit(0.U(4.W))
  val robs = Reg(Vec(ROBWidth, new ROB))
  val cur_rb = Reg(new ROB)
  val init = Wire(new ROB)
  init.id := 0.U
  init.dest := 0.S
  init.value := 0.U
  init.type_ := 0.S
  init.ready  := false.B
  val robs = RegInit(VecInit(Seq.fill(ROBWidth)(init)))
  io.out_l:=l
  io.out_r:=r
  io.out_space:=(ROBWidth-1).U-(r-l)
  io.out_robs:=robs
  io.out_reg_valid:=false.B
  io.out_store_valid:=false.B
  io.out_halt:=false.B
  
  when (io.clear_valid===true.B){//flush
    l:=0.U
    r:=0.U
  }.otherwise{
    for (i<-0 until ALU_NUM+2)//receive CDBs
    {
      for (j<-0 until ROBWidth)
      {
        when (j.U<(r-l)&&robs(l+j.U).id===io.cdbs(i).id&&robs(l+j.U).ready===false.B)
        {
            when (cdbs(i).dest=/=(-2).S)//-2 is for branch operations, -1 is for halt
            {
                robs(l+j.U).dest:=cdbs(i).dest
                robs(l+j.U).value:=cdbs(i).value
            }.otherwise{
                robs(l+j.U).value:=robs(l+j.U).value+robs(l+j.U).value+cdbs(i).value
            }
            robs(l+j.U).ready:=true.B
        }
      }
    }
    for (i<-0 until issueWidth)//receiving ROBs
    {
      when (i.U<io.valid_num){
        robs(r+i.U):=in_robs(i)
      }
    }
    r:=r+io.valid_num
    val waiting=RegInit(false.B)
    when (waiting===false.B){
        when (l=/=r && robs(l).ready===true.B){//commit
            l:=l+1.U
            val rb = Wire(new ROB)
            rb:=robs(l)
            when (rb.type_ > 0.S)//store
            {
                io.out_store_valid:=true.B
                io.out_store_addr:=rb.dest
                io.out_store_value:=rb.value
                io.out_store_mask:=MUX(rb.type_ === 1.S, 1.U, MUX(rb.type_ === 2.S, 3.U, 15.U))
                cur_rb:=rb
                waiting:=1.U
            }.elsewhen (rb.type_ === 0.S)//alu
            {
                when (rb.dest===(-1).S){//halt
                    io.out_halt:=true.B
                }.otherwise{//change register
                    io.out_reg_valid:=true.B
                    io.out_reg_id:=rb.dest.asUInt
                    io.out_reg_val:=rb.value
                    io.out_reg_prod:=rb.id
                }
            }.otherwise//branch
            {
                when (rb.value(0)===1.U)
                {        
                    io.out_flush:=true.B
                    io.out_flushval:=rb.id
                    io.out_newaddr:=rb.dest.asUInt                 
                }
            }
        }
    }.otherwise{
        when (io.store_received===true.B){
            waiting=false.B
        }.otherwise{
            io.out_store_valid:=true.B
            io.out_store_addr:=cur_rb.dest
            io.out_store_value:=cur_rb.value
            io.out_store_mask:=MUX(cur_rb.type_ === 1.S, 1.U, MUX(cur_rb.type_ === 2.S, 3.U, 15.U))
        }
    }
  }
}