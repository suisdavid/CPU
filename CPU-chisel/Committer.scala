//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
// Unused imports in the draft are not compilation errors.

package Committer

import Config.CPUConfig._
import ROB._
import CDB._
import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.RawTester.test
import _root_.circt.stage.ChiselStage

class Committer extends Module{
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
    
    // Lanes are ordered oldest to youngest, including non-register instructions.
    val out_reg_valid = Output(Vec(issueWidth,Bool()))
    val out_reg_id = Output(Vec(issueWidth,UInt(5.W)))
    val out_reg_val = Output(Vec(issueWidth,UInt(32.W)))
    val out_reg_prod = Output(Vec(issueWidth,UInt(32.W)))
    val out_commit_num = Output(UInt(log2Ceil(issueWidth+1).W))

    val out_flush = Output(Bool())
    val out_flushval = Output(UInt(32.W))
    val out_newaddr = Output(UInt(32.W))
    val out_l = Output(UInt(4.W))
    val out_r = Output(UInt(4.W))
    val out_space = Output(UInt(32.W))
    val out_robs= Output(Vec(ROBWidth,new ROB))

    val out_halt=Output(Bool())

    val out_predictor_valid = Output(Bool())
    val out_predictor_addr = Output(UInt(32.W))
    val out_predictor_res = Output(UInt(32.W))
  })
  val l = RegInit(0.U(4.W))
  val r = RegInit(0.U(4.W))
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
  io.out_reg_valid.foreach(_:=false.B)
  io.out_store_valid:=false.B
  io.out_halt:=false.B
  io.out_predictor_valid:=false.B
  
  io.out_store_addr:=0.U
  io.out_store_value:=0.U
  io.out_store_mask:=0.U
  io.out_reg_id.foreach(_:=0.U)
  io.out_reg_val.foreach(_:=0.U)
  io.out_reg_prod.foreach(_:=0.U)
  io.out_flush:=false.B
  io.out_flushval:=0.U
  io.out_newaddr:=0.U
  io.out_predictor_addr:=0.U
  io.out_predictor_res:=0.U
  val waiting=RegInit(false.B)
  val retired=WireInit(VecInit(Seq.fill(issueWidth)(false.B)))
  val proceed=Wire(Vec(issueWidth+1,Bool()))
  val branch_used=Wire(Vec(issueWidth+1,Bool()))
  proceed(0):= !io.clear_valid
  branch_used(0):=false.B
  for(i<-0 until issueWidth){
    proceed(i+1):=false.B
    branch_used(i+1):=branch_used(i)
  }
  io.out_commit_num:=PopCount(retired)

  when (io.clear_valid===true.B){//flush
    l:=0.U
    r:=0.U
    waiting:=false.B
    for(i<-0 until ROBWidth){robs(i).ready:=false.B}
  }.otherwise{
    for (i<-0 until ALU_NUM+2)//receive CDBs
    {
      for (j<-0 until ROBWidth)
      {
        when ((j.U(4.W)-l)<(r-l)&&io.cdbs(i).valid&&robs(j).id===io.cdbs(i).id&&robs(j).ready===false.B)
        {
            when (io.cdbs(i).dest=/=(-2).S)//-2 is for branch operations, -1 is for halt
            {
                robs(j).dest:=io.cdbs(i).dest
                robs(j).value:=io.cdbs(i).value
            }.otherwise{
                robs(j).value:=robs(j).value+robs(j).value+io.cdbs(i).value
            }
            robs(j).ready:=true.B
        }
      }
    }
    for (i<-0 until issueWidth)//receiving ROBs
    {
      when (i.U<io.valid_num){
        robs(ringIndex(r,i.U)):=io.in_robs(i)
      }
    }
    r:=r+io.valid_num
    for(i<-0 until issueWidth){
        val index=ringIndex(l,i.U)
        when (proceed(i) && i.U<(r-l) && robs(index).ready){//commit a ready prefix
            val rb = Wire(new ROB)
            rb:=robs(index)
            if(i==0){when(waiting){rb:=cur_rb}}
            when (rb.type_ > 0.S)//store
            {
                // Keep the existing single store transaction. Even on ACK,
                // stop this group here (in particular at the exit MMIO store).
                io.out_store_valid:=true.B
                io.out_store_addr:=rb.dest.asUInt
                io.out_store_value:=rb.value
                io.out_store_mask:=Mux(rb.type_ === 1.S, 1.U, Mux(rb.type_ === 2.S, 3.U, 15.U))
                cur_rb:=rb
                when(io.store_received){
                  retired(i):=true.B;waiting:=false.B
                }.otherwise{waiting:=true.B}
            }.elsewhen (rb.type_ === 0.S)//alu
            {
                retired(i):=true.B
                when (rb.dest===(-1).S){//halt
                    io.out_halt:=true.B
                }.otherwise{//change register
                    proceed(i+1):=true.B
                    io.out_reg_valid(i):=rb.dest=/=0.S
                    io.out_reg_id(i):=rb.dest.asUInt
                    io.out_reg_val(i):=rb.value
                    io.out_reg_prod(i):=rb.id
                }
            }.elsewhen(!branch_used(i))//one predictor update port
            {
                retired(i):=true.B
                branch_used(i+1):=true.B
                proceed(i+1):= !rb.value(0)
                when (rb.value(0)===1.U)
                {        
                    io.out_flush:=true.B
                    io.out_flushval:=rb.id
                    io.out_newaddr:=rb.dest.asUInt                 
                }
                io.out_predictor_valid := true.B
                io.out_predictor_addr := ~(rb.type_.asUInt)
                io.out_predictor_res :=  rb.value
            }
        }
        when(retired(i)){robs(index).ready:=false.B}
    }
    l:=l+io.out_commit_num
  }
}
