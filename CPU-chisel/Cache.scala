//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
// Unused imports in the draft are not compilation errors.

package Cache
import cpu2026._
import Config.CPUConfig._

import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import chisel3.util._
import _root_.circt.stage.ChiselStage

import chisel3.experimental.{ExtModule, IntParam}
import Config.CPUConfig.setBIT

class Cache(val offsetBIT: Int) extends Module{//issueWidth Icache, 1 Dcache
//Icache offset=4bit, Dache offset=2bit, tagBIT=32-setBIT-offsetBIT
    val io=IO(new Bundle{
        val load_valid = Input(Bool())//load
        val need_load = Input(Bool())//if cache miss when loading, whether need to wait for the new value from the memory
        val store_valid = Input(Bool())//store
        val addr  = Input(UInt(32.W))
        val wdata = Input(UInt(32.W))
        val wmask = Input(UInt(4.W))

        val new_valid = Input(Bool())
        val new_data = Input(UInt(32.W))//the data of the new wanted addr
        val clear_status = Input(Bool()) //no longer needs new data

        val store_received = Input(Bool())

        val out_ready = Output(Bool())
        val out_done = Output(Bool())
        val out_neednew = Output(Bool())
        val out_addr = Output(UInt(32.W))
        val out_hit = Output(Bool())//whether cache hit or not
        val out_rdata = Output(UInt(32.W))
        
        val out_wvalid = Output(Bool())
        val out_waddr = Output(UInt(32.W))//the discarded addr
        val out_wdata = Output(UInt(32.W))//the discarded data

    })
    io.out_wvalid := false.B
    val tagBIT=32-setBIT-offsetBIT
    val n = setSIZE * lineNum
    val valid = RegInit(VecInit(Seq.fill(lineNum)(VecInit(Seq.fill(setSIZE)(false.B)))))
    val dirty = RegInit(VecInit(Seq.fill(lineNum)(VecInit(Seq.fill(setSIZE)(false.B)))))
    val age = RegInit(VecInit(Seq.fill(lineNum)(VecInit(Seq.fill(setSIZE)(0.U(2.W))))))//age=0,1,2,3

    val needTag = Reg(UInt(tagBIT.W))
    val lstLoad = RegInit(false.B)
    val lstStore = RegInit(false.B)
    val lstData = RegInit(0.U(32.W))
    val lstMask = RegInit(0.U(4.W))
    val lstIdx = Reg(UInt(setBIT.W))
    val waiting = RegInit(0.U(2.W))//0: initial state, 1 waiting for new val loaded from memory, 2 waiting for the discarded dirty value to be store into memory
    val tags = Reg(Vec(lineNum,UInt(tagBIT.W)))
    val datas = Reg(Vec(lineNum,UInt(32.W)))

    val pendingAddr = Reg(UInt(32.W))
    val completedData = Reg(UInt(32.W))
    val pre_waddr = Reg(UInt(32.W))
    val pre_wdata = Reg(UInt(32.W))

    val tagRams = Seq.fill(lineNum)(Module(new SRAM(setSIZE, tagBIT, tagBIT)))
    val dataRams = Seq.fill(lineNum)(Module(new SRAM(setSIZE, 32, 8)))
    for(i<-0 until lineNum){
      for(ram<-Seq(tagRams(i),dataRams(i))){
        ram.clk:=clock;ram.en:=false.B;ram.we:=false.B
        ram.addr:=lstIdx;ram.wdata:=0.U;ram.wmask:=0.U
      }
    }
    // One indexed metadata write per way keeps the same registers and LRU policy.
    val valid_next=Wire(Vec(lineNum,Bool()));val valid_en=WireInit(VecInit(Seq.fill(lineNum)(false.B)))
    val dirty_next=Wire(Vec(lineNum,Bool()));val dirty_en=WireInit(VecInit(Seq.fill(lineNum)(false.B)))
    val age_next=Wire(Vec(lineNum,UInt(2.W)));val age_en=WireInit(VecInit(Seq.fill(lineNum)(false.B)))
    for(i<-0 until lineNum){valid_next(i):=valid(i)(lstIdx);dirty_next(i):=dirty(i)(lstIdx);age_next(i):=age(i)(lstIdx)}
    io.out_ready:=waiting===0.U && !lstLoad && !lstStore
    io.out_done:=false.B;io.out_hit:=false.B;io.out_rdata:=0.U
    io.out_neednew:=waiting===1.U
    io.out_addr:=pendingAddr
    io.out_waddr:=pre_waddr;io.out_wdata:=pre_wdata
    val tag = Wire(UInt(tagBIT.W))
    val set_idx = Wire(UInt(setBIT.W))
    val missed = Wire(Bool())
    val flag=Wire(UInt(3.W))
    val write_data = Wire(Bool())
    val data = Wire(UInt(32.W))
    val isdirty = Wire(Bool())

    write_data:=false.B
    missed:=false.B
    flag:=0.U
    data:=0.U
    isdirty:=false.B
    set_idx := io.addr(setBIT+offsetBIT-1,offsetBIT)
    tag := io.addr(31,setBIT+offsetBIT)

    when (waiting===0.U)
    {
        when(io.out_ready && (io.load_valid || io.store_valid)){
          needTag:=tag;lstLoad:=io.load_valid;lstStore:=io.store_valid
          lstData:=io.wdata;lstMask:=io.wmask;lstIdx:=set_idx;pendingAddr:=io.addr
          for(i<-0 until lineNum){
            tagRams(i).en:=true.B;tagRams(i).addr:=set_idx
            dataRams(i).en:=true.B;dataRams(i).addr:=set_idx
          }
        }
        when ((lstLoad|lstStore)===true.B)
        {
            flag:=0.U//0 for invalid
            missed:=true.B
            val threshold_age = WireDefault(0.U(2.W))
            for (i<-0 until lineNum){
                when (valid(i)(lstIdx) && tagRams(i).rdata === needTag){//cache hit, need to update age
                    io.out_hit:=true.B
                    flag:=(i+1).U
                    missed:=false.B
                    io.out_rdata:=dataRams(i).rdata
                    threshold_age:=age(i)(lstIdx)
                    age_en(i):=true.B;age_next(i):=0.U
                }
                tags(i):=tagRams(i).rdata
                datas(i):=dataRams(i).rdata
            }
            when (missed===false.B){//update age
                for (i<-0 until lineNum)
                {
                    when (age(i)(lstIdx)<threshold_age)
                    {
                       age_en(i):=true.B;age_next(i):=age(i)(lstIdx)+1.U
                    }
                }
            }
        }
        when ((lstLoad || lstStore) && missed===false.B)//hit
        {
            io.out_done:=true.B
            lstLoad:=false.B;lstStore:=false.B
            when (lstStore===true.B)//write
            {
                for (i<-0 until lineNum)
                {
                    when ((i+1).U===flag)
                    {
                        dataRams(i).clk:=clock
                        dataRams(i).en:=true.B
                        dataRams(i).we:=true.B//write
                        dataRams(i).addr:=lstIdx
                        dataRams(i).wdata:=lstData
                        dataRams(i).wmask:=lstMask
                        dirty_en(i):=true.B;dirty_next(i):=true.B
                    }
                }
            }
        }.elsewhen(missed)
        {
            when (lstStore && lstMask===15.U)
            {
                write_data:=true.B
                data:=lstData
                lstLoad:=false.B
                lstStore:=false.B
                isdirty:=true.B //dirty val
            }.otherwise
            {
                waiting:=1.U
            }
        }
    }.elsewhen (waiting===1.U){
        when (io.new_valid===true.B){//cache not hit when loading, wait for new val
            write_data:=true.B
            val bytes=Wire(Vec(4,UInt(8.W)))
            for(j<-0 until 4){bytes(j):=Mux(lstStore && lstMask(j),lstData(8*j+7,8*j),io.new_data(8*j+7,8*j))}
            data:=bytes.asUInt
            waiting:=0.U
            isdirty:=lstStore // A partial store miss must preserve untouched bytes.
        }
    }.otherwise{
        io.out_wvalid:=true.B
        when (io.store_received===true.B){
            waiting:=0.U
            io.out_done:=true.B;io.out_hit:=true.B;io.out_rdata:=completedData
        }.otherwise{
            io.out_wvalid:=true.B
            io.out_waddr:=pre_waddr
            io.out_wdata:=pre_wdata
        }
    }
    when (write_data===true.B)
    {
        val chosen = WireDefault(0.U(2.W))
        for(i<-(lineNum-1) to 0 by -1){when(age(i)(lstIdx)===(lineNum-1).U){chosen:=i.U}}
        for(i<-(lineNum-1) to 0 by -1){when(!valid(i)(lstIdx)){chosen:=i.U}}
        waiting:=0.U;lstLoad:=false.B;lstStore:=false.B
        completedData:=data
        io.out_done:=true.B;io.out_hit:=true.B;io.out_rdata:=data
        for(i<-0 until lineNum){
          when(chosen===i.U && valid(i)(lstIdx) && dirty(i)(lstIdx)){
            // Save only the selected victim. SRAM outputs belong to the original lookup.
            pre_waddr:=Cat(Mux(missed,tagRams(i).rdata,tags(i)),lstIdx,0.U(offsetBIT.W))
            pre_wdata:=Mux(missed,dataRams(i).rdata,datas(i))
            waiting:=2.U;io.out_done:=false.B;io.out_hit:=false.B
          }
        }
        for (i<-0 until lineNum)//write new val
        {
            when (i.U===chosen)
            {
                tagRams(i).clk:=clock
                tagRams(i).en:=true.B
                tagRams(i).we:=true.B
                tagRams(i).addr:=lstIdx
                tagRams(i).wdata:=needTag
                tagRams(i).wmask:=1.U

                dataRams(i).clk:=clock
                dataRams(i).en:=true.B
                dataRams(i).we:=true.B
                dataRams(i).addr:=lstIdx
                dataRams(i).wdata:=data
                dataRams(i).wmask:=15.U

                valid_en(i):=true.B;valid_next(i):=true.B
                dirty_en(i):=true.B;dirty_next(i):=isdirty
                age_en(i):=true.B;age_next(i):=0.U
            }.otherwise{
                age_en(i):=true.B;age_next(i):=Mux(age(i)(lstIdx)===(lineNum-1).U,age(i)(lstIdx),age(i)(lstIdx)+1.U)
            }
        }
    }
    // Branch recovery may cancel a speculative read, never a dirty writeback.
    when(io.clear_status && !lstStore && waiting=/=2.U){
      waiting:=0.U;lstLoad:=false.B
      io.out_hit:=false.B;io.out_done:=false.B;io.out_neednew:=false.B
    }
    for(i<-0 until lineNum){
      when(valid_en(i)){valid(i)(lstIdx):=valid_next(i)}
      when(dirty_en(i)){dirty(i)(lstIdx):=dirty_next(i)}
      when(age_en(i)){age(i)(lstIdx):=age_next(i)}
    }

}
