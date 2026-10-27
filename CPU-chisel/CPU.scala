//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused

package CPU
import Config._
import Op._
import CDB._
import RS._
import ROB._
import Cache._
import Decoder._
import Executer._
import Committer._
import Predictor._

import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import chisel3.util._
import _root_.circt.stage.ChiselStage
import coursier.internal.InMemoryCache
import Config.CPUConfig.issueWidth

class CPU extends Module{//priority: ready instruction< read data < write data < Dcache write back
    val io=IO(new Bundle{
        val araddr  = Output(UInt(32.W))
        val arvalid = Output(Bool())
        val arready = Input(Bool())

        val rdata   = Input(UInt(32.W))
        val rresp   = Input(UInt(2.W))
        val rvalid  = Input(Bool())
        val rready  = Output(Bool())

        val awaddr  = Output(UInt(32.W))
        val awvalid = Output(Bool())
        val awready = Input(Bool())

        val wdata   = Output(UInt(32.W))
        val wstrb   = Output(UInt(4.W))
        val wvalid  = Output(Bool())
        val wready  = Input(Bool())

        val bresp   = Input(UInt(2.W))
        val bvalid  = Input(Bool())
        val bready  = Output(Bool())

        val flush_valid = Input(Bool())
    })
    val init = Wire(new Register())
    init.value := 0.U(32.W)
    init.prod  := -1.S(32.W)
    val regs = RegInit(VecInit(Seq.fill(32)(init)))
    val pc = RegInit(init)
    
    val clk=RegInit(0.U(32.W))
    val halt=RegInit(false.B)
    val waiting_val=Reg(UInt(32.W))
    
    val flush=RegInit(false.B)
    val flush_val=Reg(SInt(32.W))
    val newaddr=Reg(UInt(32.W))
    val new_pc_val = Wire(UInt(32.W))
    val Icache_out_num = Wire(UInt(32.W))

    regs(0).value:=0.U(32.W)
    regs(0).prod:=-1.S(32.W)
    val opcode=Wire(SInt(32.W))

    val Icache=Seq.tabulate(issueWidth) { i =>Module(new Cache(4))}//Icache(i) manages start addr mod 16 = 4i
    val Dcache=Module(new Cache(2))
    val committer=Module(new Committer)
    val alus = Seq.tabulate(ALU_NUM) { i =>Module(new ALU(i))}
    val lsu=Module(new Executer)
    val bru=Module(new Executer)
    val mult  = Module(new Mult)
    val div  = Module(new Divider)
    val issuer = Module(new Issuer)
    val decoder = Module(new Decoder)
    val predictor = Module(new Predictor)

    io.arvalid:=false.B
    io.rready:=true.B
    io.awvalid:=false.B
    io.wvalid:=false.B
    io.bready:=true.B

    halt:=halt|commiter.io.out_halt
    flush:=committer.io.out_flush
    flush_val:=committer.io.out_flushval
    newaddr:=committerio.out_newaddr
    
    clk:=clk+1.U(32.W)
    
    //initialization
    for (i<-0 until ALU_NUM){
        alus(i).io.clear_valid:=false.B
    }
    bru.io.clear_valid:=false.B
    lsu.io.clear_valid:=false.B
    committer.io.clear_valid:=false.B
    issuer.io.valid_num:=0.U
    decoder.io.valid_num:=0.U

    for (i<-0 until issueWidth){
        Icache(i).io.load_valid:=false.B
        Icache(i).io.store_valid:=false.B
        Icache(i).io.new_valid:=false.B
        Icache(i).io.store_received:=false.B
        Icache(i).io.clear_status:=false.B
    }
    Dcache.io.load_valid:=false.B
    Dcache.io.store_valid:=false.B
    Dcache.io.new_valid:=false.B
    Dcache.io.store_received:=false.B
    Dcache.io.clear_status:=false.B


    val fetcher_waiting = RegInit(0.U(2.W))//0: initial state; 1: set arvalid=true&araddr, waiting for arready; 2: received arready=true, set rready=true, waiting for rvalid; when received rvalid, return to 0
    val hits = Wire(Vec(issueWidth+1,UInt(3.W)))
    val fetcher_next = Wire(0.U(2.W))
    val lsu_waiting=RegInit(0.U(2.W))//0 initial state, 1 waiting for Dcache, 2 waiting for memory, 3 got memory, storing into Dcache
    val lsu_lst_addr=Reg(UInt(32.W))
    val committer_waiting=RegInit(0.U(2.W))//0 initial state, 1 waiting for Dcache, 2 waiting for Dcache write back
    val Dcache_waiting = RegInit(0.U(2.W))

    when (halt===false.B){
        //Dcache write back  
        when (Dcache_waiting===0.U)
        {
            when (Dcache.io.out_wvalid===true.B&&fetcher_waiting===0.U&&lsu_waiting===0.U)
            {//definitely happens, no need to worry about rolling back
                io.awaddr:=Dcache.io.out_waddr
                io.wdata:=Dcache.io.out_wdata
                io.wstrb:=15.U
                io.wvalid:=true.B
                io.awvalid:=true.B
                Dcache_waiting:=1.U
            }
        }.elsewhen(Dcache_waiting===1.U){
            when (io.wready:=true.B&&io.awready:=true.B)
            {
                Dcache_waiting:=2.U
            }.otherwise
            {
                io.awaddr:=Dcache.io.out_waddr
                io.wdata:=Dcache.io.out_wdata
                io.wstrb:=15.U
                io.wvalid:=true.B
                io.awvalid:=true.B
            }
        }.otherwise{
            when (io.bvalid:=true.B)
            {
                Dcache.io.store_received:=true.B
                Dcache_waiting:=0.U
            }
        }


        when (flush===true.B){//flush
            issuer.io.nex_want:=newaddr
            pc.value:=newaddr
            for (i<-0 until ALU_NUM)
            {
                alus(i).io.clear_valid:=true.B
                alus(i).io.clear_val=flush_val
            }
            mult.io.clear_valid:=true.B
            div.io.clear_valid:=true.B
            bru.io.clear_valid:=true.B
            bru.io.clear_val=flush_val
            lsu.io.clear_valid:=true.B
            lsu.io.clear_val=flush_val
            for (i<-0 until 32){
                when (regs(i).prod>flush_val){
                    regs(i).prod:=-1.S(32.W)
                }
            }
            committer.io.clear_valid:=true.B
            fetcher_waiting:=0.U
            lsu_waiting:=0.U
            committer_waiting:=0.U
            Dcache_waiting:=0.U
            
            for (i<-0 until issueWidth){
                Icache(i).io.clear_status:=true.B
            }
            Dcache.io.clear_status:=true.B

            flush:=false.B
        }.otherwise{//main cycle
            //fetcher
            fetcher_next:=fetcher_waiting
            new_pc_val:=pc.value
            when(fetcher_waiting===0.U)
            {
                hits(0):=0.U
                for (l<-0 until issueWidth){
                    when (pc.value(3,2)===l.U){
                        for (i<-0 until issueWidth){
                            hits(i+1) := Mux(hits(i)===i.U,(i+1).U,hits(i))
                            decoder.io.id((l+i)%issueWidth):=pc.value+(4*i).U
                            decoder.io.opcode((l+i)%issueWidth):=Icache((l+i)%issueWidth).io.out_rdata
                            hits(i+1) := Mux(Icache((l+i)%issueWidth).io.out_hit===true.B&&hits(i)===i.U,(i+1).U, hits(i))
                        }
                    }
                }
                decoder.io.valid_num := hits(issueWidth)
                when (hits(issueWidth)===0.U&&Dcache_waiting===false.B&&Dcache.io.out_wvalid===false.B&&lsu_waiting===0.U&&lsu.lsuIO.out_valid===false.B){//read instructions from memory
                    io.araddr:=pc.value
                    io.arvalid:=true.B
                    fetcher_waiting:=1.U
                    fetcher_next:=1.U
                }
                new_pc_val := pc.value+(hits(issueWidth)<<2.U)
            }.elsewhen(fetcher_waiting===1.U){
                    when (io.arready===true.B){
                        fetcher_waiting:=2.U
                        fetcher_next:=2.U
                        io.rready:=true.B
                    }.otherwise{
                        io.araddr:=pc.value
                        io.arvalid:=true.B
                    }
                }.elsewhen(fetcher_waiting==2.U){
                    when (io.rvalid===true.B){
                        fetcher_waiting:=0.U
                        fetcher_next:=0.U
                        decoder.io.valid_num:=1.U
                        decoder.io.id(0):=pc.value
                        decoder.io.opcode(0):=io.rdata
                        new_pc_val:=pc.value+4.U
                        //write in Icache
                        for (i<-0 until issueWidth)
                        {//other Icaches no longer need to wait for new_data
                            when (pc.value(3,2)===i.U)
                            {
                                Icache(i).io.new_valid := true.B
                                Icache(i).io.new_data := io.rdata
                            }
                        }
                    }.otherwise{
                        io.rready:=true.B
                    }
                }

                //predictor
                predictor.io.qaddrs:= decoder.io.out_id
                //handling issuer input
                issuer.io.valid_num:=decoder.out_num
                issuer.io.id:=decoder.io.out_id
                issuer.io.cur_op:=decoder.io.out_op
                issuer.io.predictor_result := predictor.io.out

                for (i<-0 until 32){
                    issuer.io.reg(i):=regs(i)
                }

                for (i<-0 until ALU_NUM){
                    issuer.io.executer_space(i):=alus(i).io.out_space
                }
                issuer.io.robs:=committer.io.out_robs
                issuer.io.executer_space(ALU_NUM):=lsu.io.out_space
                issuer.io.executer_space(ALU_NUM+1):=bru.io.out_space
                issuer.io.committer_space:=committer.io.out_space

                //handling issuer output
                //executer input
                val num=Wire(UInt(32.W))
                val cnts=Wire(Vec((issueWidth+1)*(ALU_NUM+2),UInt(32.W)))
                num:=issuer.io.out_valid
                for (i<-0 until ALU_NUM+2){
                    cnts(i):=0.U(32.W)
                }
                for (i<-0 until issueWidth){
                    for (j<-0 until ALU_NUM+2){
                        cnts((i+1)*(ALU_NUM+2)+j):=cnts(i*(ALU_NUM+2)+j)
                    }
                    when (i.U<num){
                        val tp= Wire(UInt(32.W))
                        tp := issuer.io.out_executer_RS(i)
                        when (tp<ALU_NUM.U){
                            for (j<-0 until ALU_NUM)//indexing workaround 
                            {
                                when (j.U===tp){
                                    alus(j).io.rs(cnts(i*(ALU_NUM+2)+j)):=issuer.io.out_executer_RS(i)
                                }
                            }
                        }.elsewhen(tp==ALU_NUM.U){
                            lsu.io.rs(cnts(i*(ALU_NUM+2)+ALU_NUM)):=issuer.io.out_executer_RS(i)
                        }.otherwise{
                            bru.io.rs(cnts(i*(ALU_NUM+2)+ALU_NUM+1)):=issuer.io.out_executer_RS(i)
                        }
                        cnts(((i+1)*(ALU_NUM+2)).U+tp):=cnts(i*(ALU_NUM+2).U+tp)+1.U
                    }
                }
                for (i<-0 until ALU_NUM){
                    alus(i).io.valid_num:=cnts(issueWidth*(ALU_NUM+2)+i)
                }
                lsu.io.valid_num:=cnts(issueWidth*(ALU_NUM+2)+ALU_NUM)
                bru.io.valid_num:=cnts(issueWidth*(ALU_NUM+2)+ALU_NUM+1)
                //committer input
                committer.io.valid_num:=num
                committer.io.in_robs:= issuer.io.out_rob

                //updating register prods
                for (i<-0 until 32){
                    regs(i).prod:=issuer.io.out_prod(i)
                }
                when (issuer.io.out_pc_changed===true.B)
                {
                    new_pc_val:= issuer.io.out_new_pc_val
                    fetcher_waiting:=0.U
                    fetcher_next:=0.U
                    for (i<-0 until issueWidth){
                        Icache(i).io.clear_status:=true.B
                    }
                }
                pc.value:=new_pc_val
                when (fetcher_next===0.U)
                {
                    for (l<-0 until issueWidth){
                        when (new_pc_val(3,2)===l.U){
                            for (i<-0 until issueWidth){
                                Icache((i+l)%issueWidth).io.load_valid:=true.B
                                Icache((i+l)%issueWidth).io.addr:=new_pc_val+(i*4).U
                            }
                        }
                    }
                }

                //handling executer output
                //mult,div
                val mult_used=Wire(Vec(ALU_NUM+1,Bool()))
                val div_used=Wire(Vec(ALU_NUM+1,Bool()))
                mult_used(0):=false.B
                div_used(0):=false.B
                for(i<-0 until ALU_NUM){
                    alus(i).aluIO.mult_valid:=mult.io.ready
                    alus(i).aluIO.mult_res:=mult.io.c
                    alus(i).aluIO.mult_received:=mult.io.received
                    alus(i).aluIO.div_valid:=div.io.ready
                    alus(i).aluIO.div_res:=div.io.c
                    alus(i).aluIO.div_received:=div.io.received
    
                    mult_used(i+1):=mult_used(i)
                    div_used(i+1):=div_used(i)
                    when (alus(i).aluIO.out_mult_valid===true.B&&mult_used(i)===false.B){
                        mult.io.a:=alus(i).out_mult_a
                        mult.io.b:=alus(i).out_mult_b
                        mult.io.op:=alus(i).out_mult_op
                        mult.io.valid:=(i+1).U
                        mult_used(i+1):=true.B
                    }
                    when (alus(i).aluIO.out_div_valid===true.B&&div_used(i)===false.B){
                        div.io.a:=alus(i).out_div_a
                        div.io.b:=alus(i).out_div_b
                        div.io.op:=alus(i).out_div_op
                        div.io.valid:=(i+1).U
                        div_used(i+1):=true.B
                    }
                }

                //lsu
                lsu.lsuIO.addr_valid:=false.B
                lsu.lsuIO.l:=committer.out_l
                lsu.lsuIO.r:=committer.out_r
                lsu.lsuIO.robs:=committer.out_robs
                when (lsu_waiting===0.U){
                    when (lsu.lsuIO.out_valid===true.B&&Dcache_waiting===0.U&&Dcache.io.out_wvalid===false.B&&committer_waiting===0.U&&committer.io.out_store_valid===false.B){//can load only when no store
                        Dcache.io.load_valid:=true.B
                        Dcache.io.addr := lsu.lsuIO.out_addr
                        lsu_lst_addr := lsu.lsuIO.out_addr
                        lsu_waiting:=1.U
                        lsu.lsuIO.addr_received:=true.B
                    }
                }.elsewhen(lsu_waiting===1.U){
                    when (Dcache.io.out_hit===true.B){
                        lsu_waiting:=0.U
                        io.rready:=true.B
                        lsu.lsuIO.addr_valid:=true.B
                        lsu.lsuIO.addr_value:=Dcache.io.out_rdata
                    }.otherwise{//fetcher is not using memory
                        when (fetcher_next===0.U)
                        {
                            io.araddr:=lsu_lst_addr
                            io.arvalid:=true.B
                        }
                        lsu_waiting:=2.U
                    }
                }.elsewhen(lsu_waiting===2.U&&fetcher_next===0.U){
                    when (io.arready===true.B){
                        io.rready:=true.B
                        lsu_waiting:=3.U
                    }.otherwise{
                        io.araddr:=lsu_lst_addr
                        io.arvalid:=true.B
                    }
                }.otherwise{
                    when (io.rvalid===true.B){
                        lsu_waiting:=0.U
                        lsu.lsuIO.addr_valid:=true.B
                        lsu.lsuIO.addr_value:=io.rdata
                        Dcache.io.new_valid:=true.B
                        Dcache.io.new_data:=io.rdata
                    }.otherwise{
                        io.rready:=true.B
                    }
                }

                //broadcast CDBs
                for (i<-0 until ALU_NUM){
                    for (j<-0 until ALU_NUM){
                        alus(j).io.cdbs(i):=RegNext(alus(i).io.out_cdb)
                    }
                    lsu.io.cdbs(i):=RegNext(alus(i).io.out_cdb)
                    bru.io.cdbs(i):=RegNext(alus(i).io.out_cdb)
                    commiter.io.cdbs(i):=RegNext(alus(i).io.out_cdb)
                }
                for (j<-0 until ALU_NUM)
                {
                    alus(j).io.cdbs(ALU_NUM):=RegNext(lsu.io.out_cdb)
                }
                lsu.io.cdbs(ALU_NUM):=RegNext(lsu.io.out_cdb)
                bru.io.cdbs(ALU_NUM):=RegNext(lsu.io.out_cdb)
                commiter.io.cdbs(ALU_NUM):=RegNext(lsu.io.out_cdb)
                for (j<-0 until ALU_NUM)
                {
                    alus(j).io.cdbs(ALU_NUM+1):=RegNext(bru.io.out_cdb)
                }
                lsu.io.cdbs(ALU_NUM+1):=RegNext(bru.io.out_cdb)
                bru.io.cdbs(ALU_NUM+1):=RegNext(bru.io.out_cdb)
                commiter.io.cdbs(i):=RegNext(bru.io.out_cdb)

                //committer
                committer.io.store_received:=false.B
                when (committer_waiting===0.U){
                    when (committer.io.out_store_valid===true.B&&Dcache_waiting===0.U&&Dcache.io.out_wvalid===false.B){//store
                        Dcache.io.store_valid := true.B
                        Dcache.io.addr := committer.io.out_store_addr
                        Dcache.io.wdata := committer.io.out_store_value
                        Dcache.io.wmask := committer.io.out_store_mask
                        committer_waiting:=1.U
                    }.elsewhen (committer.io.out_reg_valid===true.B)
                    {
                        val id=Wire(Uint(5.W))
                        id:=committer.io.out_reg_valid
                        regs(id).value := committer.io.out_reg_val
                        when (regs(id).prod.asUInt === commiter.io.out_reg_prod&&issuer.io.out_prod_valid(id)===false.B){//not modified by issuer
                            regs(id).prod := (-1).S
                        }
                    }
                }.elsewhen (committer_waiting===1.U){
                    when (Dcache.io.out_hit===true.B){
                        committer_waiting:=0.U
                        committer.io.store_received:=true.B
                    }.otherwise{
                        committer_waiting:=2.U
                    }
                }.otherwise{
                    when (Dcache_waiting===0.U&&Dcache.io.out_wvalid===false.B){
                        committer_waiting:=0.U
                        committer.io.store_received:=true.B
                    }
                }

                //predictor update
                predictor.io.uvalid := committer.io.out_predictor_valid
                predictor.io.uaddr := committer.io.out_predictor_addr
                predictor.io.ures := committer.io.out_predictor_res



        }
    }.otherwise{//halt
        io.awaddr:=0x80000000.U(32.W)
        io.wstrb:=15.U(4.W)
        io.wdata:=regs(10).value(7,0)
        io.wvalid:=true.B
        io.awvalid:=true.B
    }
}