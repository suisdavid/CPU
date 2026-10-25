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
import Decoder._
import EXecuter._
import Committer._
import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import chisel3.util._
import _root_.circt.stage.ChiselStage
import coursier.internal.InMemoryCache
import Config.CPUConfig.issueWidth

class CPU extends Module{//priority: ready instruction< read data < write data
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
    val fetcher_waiting = RegInit(0.U(2.W))//0: initial state; 1: set arvalid=true&araddr, waiting for arready; 2: received arready=true, set rready=true, waiting for rvalid; when received rvalid, return to 0
    val flush=RegInit(false.B)
    val flush_val=Reg(SInt(32.W))
    val newaddr=Reg(UInt(32.W))
    val new_pc_val = Wire(UInt(32.W))
    val Icache_out_num = Wire(UInt(32.W))

    regs(0).value:=0.U(32.W)
    regs(0).prod:=-1.S(32.W)
    val opcode=Wire(SInt(32.W))

    val Icache=Module(new Cache)
    val Dcache=Module(new Cache)
    val committer=Module(new Committer)
    val alus = Seq.tabulate(ALU_NUM) { i =>Module(new ALU(i))}
    val lsu=Module(new Executer)
    val bru=Module(new Executer)
    val mult  = Module(new Mult)
    val div  = Module(new Divider)
    val issuer=Module(new Issuer)
    val decoder=Module(new Decoder)

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

    when (halt===false.B){
        when (flush===true.B){
            issuer.io.nex_want:=newaddr
            pc.value:=newaddr
            for (i<-0 until ALU_NUM)
            {
                alus(i).io.clear_valid:=true.B
                alus(i).io.clear_val=flush_val
            }
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
            flush:=false.B
        }.otherwise{//main cycle
            //fetcher
            when(fetcher_waiting===0.U)
            {
                Icache.io.addr:=pc.value
                for (i<-0 until issueWidth){
                    decoder.io.id(i):=Icache.io.output_id(i)
                    decoder.io.opcode(i):=Icache.io.output_value(i)
                }
                Icache_out_num := Icache.io.out_num
                decoder.io.valid_num:= Icache_out_num
                when (Icache_out_num===0.U&&lsu.lsuIO.out_valid===false.B&&committer.io.out_store_valid===false.B){
                    io.araddr:=pc.value
                    io.arvalid:=true.B
                    fetcher_waiting:=1.U
                }
            }.elsewhen(fetcher_waiting===1.U){
                    when (io.arready===true.B){
                        fetcher_waiting:=2.U(2.W)
                        io.rready:=true.B
                    }.otherwise{
                        io.araddr:=pc.value
                        io.arvalid:=true.B
                    }
                }.elsewhen(fetcher_waiting==2.U){
                    when (io.rvalid===true.B){
                        fetcher_waiting:=0.U(2.W)
                        decoder.io.valid_num:=1.U(32.W)
                        decoder.io.id(0):=pc.value
                        decoder.io.opcode(0):=io.rdata
                    }.otherwise{
                        io.rready:=true.B
                    }
                }
                //handling issuer input
                issuer.io.valid_num:=decoder.out_num
                for (i<-0 until issueWidth){
                    issuer.io.id(i):=decoder.io.out_id(i)
                    issuer.io.cur_op(i):=decoder.io.out_op(i)
                }
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
                new_pc_val:= issuer.io.out_new_pc_val
                pc.value:=new_pc_val
                Icache.io.valid:=true.B
                Icache.io.addr:=new_pc_val//retrieve W opcodes starting from io.addr





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
                lsu_waiting=RegInit(0.U(2.W))
                when (lsu_waiting===0.U){
                    when (lsu.lsuIO.out_valid===true.B&&committer.io.out_store_valid===false.B){
                        io.araddr:=lsu.lsuIO.out_addr
                        io.arvalid:=true.B
                        lsu_waiting:=1.U
                    }
                }.elsewhen(lsu_waiting===1.U){
                    when (io.arready===true.B){
                        lsu_waiting:=2.U
                        io.rready:=true.B
                        lsu.lsuIO.addr_received:=true.B
                    }.otherwise{
                        io.araddr:=lsu.lsuIO.out_addr
                        io.arvalid:=true.B
                    }
                }.otherwise{
                    when (io.rvalid===true.B){
                        lsu_waiting:=0.U(2.W)
                        lsu.lsuIO.addr_valid:=true.B
                        lsu.lsuIO.addr_value:=io.rdata
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
                committer_waiting=RegInit(0.U(2.W))//0 initial state, 1 waiting for wready and awready, 2 waiting for bvalid
                when (committer_waiting===0.U){
                    when (committer.io.out_store_valid===true.B){//store
                        io.awaddr:=committer.io.out_store_addr
                        io.wdata:=committer.io.out_store_value
                        io.wstrb:=committer.io.out_store_mask
                        io.wvalid:=true.B
                        io.awvalid:=true.B
                        committer_waiting:=1.U
                    }.elsewhen (committer.io.out_reg_valid===true.B)
                    {
                        regs(committer.io.out_reg_addr).value := committer.io.out_reg_val
                        when (regs(committer.io.out_reg_addr).prod.asUInt === commiter.io.out_reg_prod){
                            regs(committer.io.out_reg_addr).prod := (-1).S
                        }
                    }
                }.elsewhen(committer_waiting===1.U){
                    when (io.wready:=true.B&&io.awready:=true.B){
                        committer_waiting:=2.U
                    }.otherwise{
                        io.awaddr:=committer.io.out_store_addr
                        io.wdata:=committer.io.out_store_value
                        io.wstrb:=committer.io.out_store_mask
                        io.wvalid:=true.B
                        io.awvalid:=true.B
                    }
                }.otherwise{
                    when (io.bvalid:=true.B)
                    {
                        committer.io.store_received:=true.B
                        committer_waiting:=0.U
                    }
                }

        }
    }.otherwise{//halt
        io.awaddr:=0x80000000.U(32.W)
        io.wstrb:=15.U(4.W)
        io.wdata:=regs(10).value(7,0)
        io.wvalid:=true.B
        io.awvalid:=true.B
    }
}