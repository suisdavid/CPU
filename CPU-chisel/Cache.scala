//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused

package Cache
import cpu2026._
import Config._

import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import chisel3.util._
import _root_.circt.stage.ChiselStage
import coursier.internal.InMemoryCache
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

        val out_hit = Output(Bool())//whether cache hit or not
        val out_rdata = Output(UInt(32.W))
        
        val out_wvalid = Output(Bool())
        val out_waddr = Output(UInt(32.W))//the discarded addr
        val out_wdata = Output(UInt(32.W))//the discarded data

    })
    io.out_wvalid := false.B
    val n = setSIZE * lineNum
    val valid = RegInit(VecInit(Seq.fill(n)(false.B)))
    val dirty = RegInit(VecInit(Seq.fill(n)(false.B)))
    val age = RegInit(VecInit(Seq.fill(n)(0.U(2.W))))//age=0,1,2,3

    val needTag = Reg(UInt(tagBIT.W))
    val lstLoad = RegInit(false.B)
    val lstStore = RegInit(false.B)
    val lstData = RegInit(0.U(4.W))
    val lstMask = RegInit(0.U(4.W))
    val lstIdx = Reg(UInt(setBIT.W))
    val waiting = RegInit(0.U(2.W))//0: initial state, 1 waiting for new val loaded from memory, 2 waiting for the discarded dirty value to be store into memory
    val tags = Reg(Vec(lineNum,UInt(tagBIT.W)))
    val datas = Reg(Vec(lineNum,UInt(32.W)))

    val pre_waddr = Reg(UInt(32.W))
    val pre_wdata = Reg(UInt(32.W))

    val tagBIT=32-setBIT-offsetBIT
    val tagRams = Seq.fill(lineNum)(Module(new SRAM(setSIZE, tagBIT, tagBIT)))
    val dataRams = Seq.fill(lineNum)(Module(new SRAM(setSIZE, 32, 8)))
    for (i<-0 until lineNum){
        tagRams(i).en:=false.B
        dataRams(i).en:=false.B
    }
    val tag = Wire(UInt(tagBIT.W))
    val set_idx = Wire(UInt(setBIT.W))
    val missed = Wire(Bool())
    val flag=Wire(UInt(3.W))
    val write_data = Wire(Bool())
    val data = Wire(UInt(32.W))
    val isdirty = Wire(Bool())

    write_data:=false.B
    missed:=false.B
    set_idx := io.raddr(setBIT+offsetBIT-1,offsetBIT)
    tag := io.raddr(31,setBIT+offsetBIT)

    when (waiting===0.U||(waiting===1.U&&io.clear_status===true.B))
    {
        waiting:=0.U
        needTag := tag
        lstLoad := io.load_valid
        lstStore := io.store_valid
        lstData := io.wdata
        lstMask := io.wmask
        lstIdx := set_idx
        when ((lstLoad|lstStore)===true.B)
        {
            flag:=0.U//0 for invalid
            missed:=true.B
            val threshold_age = Wire(UInt(2.U))
            for (i<-0 until lineNum){
                when (tagRams(i).rdata === needTag){//cache hit, need to update age
                    io.out_hit:=true.B
                    flag:=(i+1).U
                    missed:=false.B
                    io.out_rdata:=dataRams(i).rdata
                    threshold_age:=age((i*SETSIZE).U+lstIdx)
                    age((i*SETSIZE).U+lstIdx):=0.U
                }
                tags(i):=tagRams(i).rdata
                datas(i):=dataRams(i).rdata
            }
            when (missed===false.B){//update age
                for (i<-0 until lineNum)
                {
                    when (age((i*SETSIZE).U+lstIdx)<threshold_age)
                    {
                       age((i*SETSIZE).U+lstIdx):=age((i*SETSIZE).U+lstIdx)+1.U
                    }
                }
            }
        }
        when (missed===false.B)//hit
        {
            when (lstStore===true.B)//write
            {
                for (i<-0 until lineNum)
                {
                    when ((i+1).U===flag)
                    {
                        dataRems(i).clk:=clock
                        dataRams(i).en:=true.B
                        dataRams(i).we:=true.B//write
                        dataRams(i).addr:=lstIdx
                        dataRams(i).wdata:=lstData
                        dataRams(i).wmask:=lstMask
                        dirty((i*setSIZE).U+lstIdx):=true.B
                    }
                }
            }.elsewhen ((io.load_valid|io.store_valid)===true.B)
            {
                for (i<-0 until lineNum){
                    tagRams(i).clk:=clock
                    tagRams(i).en:=true.B
                    tagRams(i).we:=false.B
                    tagRams(i).addr:=set_idx
                    dataRams(i).clk:=clock
                    dataRams(i).en:=true.B
                    dataRams(i).we:=false.B
                    dataRams(i).addr:=set_idx
                }
            }
        }.otherwise
        {
            when (lstStore===true.B)
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
            data:=io.new_data
            waiting:=0.U
            lstLoad:=false.B
            lstStore:=false.B
            isdirty:=false.B//new val loaded from memory, not dirty
        }
    }.otherwise{
        when (io.store_received===true.B){
            waiting:=0.U
        }.otherwise{
            io.out_wvalid:=true.B
            io.out_waddr:=pre_waddr
            io.out_wdata:=pre_wdata
        }
    }
    when (write_data===true.B)
    {
        val chosen = Wire(UInt(2.W))
        for (i<-(lineNum-1) to 0 by -1){
            when (valid((i*SETSIZE).U+lstIdx)===false.B){
                chosen:=i.U
            }.elsewhen (age((i*SETSIZE).U+lstIdx)===(lineNum-1).U){//maxage,need to be replaced
                chosen:=i.U
                when (dirty((i*SETSIZE).U+lstIdx)===true.B){
                    io.out_wvalid:=true.B
                    when (missed===true.B)//missed in the current cycle
                    {
                        io.out_waddr:=(Cat(tagRams(i).rdata,lstIdx)<<offsetBIT.U)
                        pre_waddr:=(Cat(tagRams(i).rdata,lstIdx)<<offsetBIT.U)
                        io.out_wdata:=dataRams(i).rdata
                        pre_wdata:=dataRams(i).rdata
                    }.otherwise{
                        io.out_waddr:=(Cat(tags(i).rdata,lstIdx)<<offsetBIT.U)
                        pre_waddr:=(Cat(tags(i).rdata,lstIdx)<<offsetBIT.U)
                        io.out_wdata:=datas(i).rdata
                        pre_wdata:=datas(i).rdata
                    }
                    waiting:=2.U
                }
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

                dataRems(i).clk:=clock
                dataRams(i).en:=true.B
                dataRams(i).we:=true.B
                dataRams(i).addr:=lstIdx
                dataRams(i).wdata:=data
                dataRams(i).wmask:=lstMask

                valid((i*setSIZE).U+lstIdx):=true.B
                dirty((i*setSIZE).U+lstIdx):=isdirty
                age((i*setSIZE).U+lstIdx):=0.U
            }.otherwise{
                age((i*setSIZE).U+lstIdx):=age((i*setSIZE).U+lstIdx)+1.U
            }
        }
    }
}
