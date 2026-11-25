//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
// Unused imports in the draft are not compilation errors.

package CPU
import Config.CPUConfig._
import Op._
import CDB._
import RS._
import ROB._
import Cache._
import Decoder._
import Executer._
import Committer._
import Predictor._
import Register._
import Issuer._

import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import chisel3.util._
import _root_.circt.stage.ChiselStage

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

    regs(0).value:=0.U(32.W)
    regs(0).prod:=(-1).S(32.W)

    val Icache=Seq.tabulate(issueWidth) { i =>Module(new Cache(4))}//Icache(i) manages start addr mod 16 = 4i
    val Dcache=Module(new Cache(2))
    val committer=Module(new Committer)
    val alus = Seq.tabulate(ALU_NUM) { i =>Module(new ALU(i))}
    val lsu=Module(new LSU)
    val bru=Module(new BRU)
    val mult  = Module(new Mult)
    val div  = Module(new Divider)
    val issuer = Module(new Issuer)
    val decoder = Module(new Decoder)
    val predictor = Module(new Predictor)

    // AXI transactions keep their owner and payload until the response, even on flush.
    val fetcher_waiting = RegInit(0.U(2.W)) // 0 idle, 1 AR, 2 R
    val read_owner = Reg(UInt(3.W)) // I-cache bank or issueWidth for D-cache
    val read_addr = Reg(UInt(32.W))
    val read_discard = RegInit(false.B)
    val Dcache_waiting = RegInit(0.U(2.W)) // 0 idle, 1 AW/W, 2 B
    val write_addr = Reg(UInt(32.W));val write_data=Reg(UInt(32.W));val write_mask=Reg(UInt(4.W))
    val write_mmio=RegInit(false.B)
    val aw_sent=RegInit(false.B);val w_sent=RegInit(false.B)
    val lsu_waiting=RegInit(false.B);val lsu_lst_addr=Reg(UInt(32.W));val lsu_discard=RegInit(false.B)
    val committer_waiting=RegInit(false.B)
    val fetch_active=RegInit(false.B)
    val fetch_done=RegInit(VecInit(Seq.fill(issueWidth)(false.B)))
    val fetch_data=Reg(Vec(issueWidth,UInt(32.W)))
    val redirect=flush || issuer.io.out_pc_val_changed || io.flush_valid
    val redirect_addr=Mux(flush || io.flush_valid,newaddr,issuer.io.out_new_pc_val)

    io.arvalid:=fetcher_waiting===1.U;io.araddr:=read_addr
    io.rready:=fetcher_waiting===2.U
    io.awvalid:=Dcache_waiting===1.U && !aw_sent;io.awaddr:=write_addr
    io.wvalid:=Dcache_waiting===1.U && !w_sent;io.wdata:=write_data;io.wstrb:=write_mask
    io.bready:=Dcache_waiting===2.U
    halt:=halt|committer.io.out_halt
    flush:=committer.io.out_flush
    flush_val:=committer.io.out_flushval.asSInt
    newaddr:=committer.io.out_newaddr
    clk:=clk+1.U
    val executers=alus.toSeq ++ Seq(lsu,bru)
    val cdbs=RegInit(VecInit(Seq.fill(ALU_NUM+2)(0.U.asTypeOf(new CDB))))
    for(i<-0 until ALU_NUM+2){
      cdbs(i):=executers(i).io.out_cdb
      executers(i).io.cdbs:=cdbs
      executers(i).io.clear_valid:=flush || io.flush_valid
      executers(i).io.clear_id:=flush_val
      executers(i).io.rs.foreach(_:=0.U.asTypeOf(new RS))
    }
    committer.io.cdbs:=cdbs
    committer.io.clear_valid:=flush || io.flush_valid
    committer.io.store_received:=false.B
    issuer.io.cdbs:=cdbs
    issuer.io.clear_valid:=flush || io.flush_valid
    issuer.io.clear_pc:=newaddr
    issuer.io.valid_num:=decoder.io.out_num
    issuer.io.id:=decoder.io.out_id
    issuer.io.cur_op:=decoder.io.out_op
    issuer.io.predictor_result:=predictor.io.out
    issuer.io.robs:=committer.io.out_robs
    issuer.io.committer_space:=committer.io.out_space
    for(i<-0 until ALU_NUM+2){issuer.io.executer_space(i):=executers(i).io.out_space}
    for(i<-0 until 32){
      issuer.io.reg(i):=regs(i)
      for(lane<-0 until issueWidth){
        when(committer.io.out_reg_valid(lane) && committer.io.out_reg_id(lane)===i.U && i.U=/=0.U){
          // Later lanes are younger and win same-register writeback/bypass.
          issuer.io.reg(i).value:=committer.io.out_reg_val(lane)
          when(regs(i).prod.asUInt===committer.io.out_reg_prod(lane)){issuer.io.reg(i).prod:=(-1).S}
        }
      }
    }
    decoder.io.clear_valid:=redirect
    decoder.io.valid_num:=0.U
    decoder.io.id.foreach(_:=0.U);decoder.io.opcode.foreach(_:=0.U)
    predictor.io.qaddrs:=decoder.io.out_id
    predictor.io.uvalid:=committer.io.out_predictor_valid
    predictor.io.uaddr:=committer.io.out_predictor_addr
    predictor.io.ures:=committer.io.out_predictor_res
    mult.io.a:=0.U;mult.io.b:=0.U;mult.io.op:=false.B;mult.io.valid:=0.U
    div.io.a:=0.U;div.io.b:=0.U;div.io.op:=false.B;div.io.valid:=0.U
    mult.io.clear_valid:=flush || io.flush_valid;div.io.clear_valid:=flush || io.flush_valid
    lsu.lsuIO.l:=committer.io.out_l;lsu.lsuIO.r:=committer.io.out_r;lsu.lsuIO.robs:=committer.io.out_robs
    lsu.lsuIO.addr_valid:=false.B;lsu.lsuIO.addr_received:=false.B;lsu.lsuIO.addr_value:=0.U
    for(cache<-Icache.toSeq :+ Dcache){
      cache.io.load_valid:=false.B;cache.io.store_valid:=false.B;cache.io.need_load:=true.B
      cache.io.addr:=0.U;cache.io.wdata:=0.U;cache.io.wmask:=0.U
      cache.io.new_valid:=false.B;cache.io.new_data:=io.rdata
      cache.io.store_received:=false.B;cache.io.clear_status:=false.B
    }

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
                        tp := issuer.io.out_executer_type(i)
                        when (tp<ALU_NUM.U){
                            for (j<-0 until ALU_NUM)//indexing workaround 
                            {
                                when (j.U===tp){
                                    alus(j).io.rs(cnts(i*(ALU_NUM+2)+j)(1,0)):=issuer.io.out_executer_RS(i)
                                }
                            }
                        }.elsewhen(tp===ALU_NUM.U){
                            lsu.io.rs(cnts(i*(ALU_NUM+2)+ALU_NUM)(1,0)):=issuer.io.out_executer_RS(i)
                        }.otherwise{
                            bru.io.rs(cnts(i*(ALU_NUM+2)+ALU_NUM+1)(1,0)):=issuer.io.out_executer_RS(i)
                        }
                        for(j<-0 until ALU_NUM+2){when(tp===j.U){cnts((i+1)*(ALU_NUM+2)+j):=cnts(i*(ALU_NUM+2)+j)+1.U}}
                    }
                }
                for (i<-0 until ALU_NUM){
                    alus(i).io.valid_num:=cnts(issueWidth*(ALU_NUM+2)+i)
                }
                lsu.io.valid_num:=cnts(issueWidth*(ALU_NUM+2)+ALU_NUM)
                bru.io.valid_num:=cnts(issueWidth*(ALU_NUM+2)+ALU_NUM+1)
                //committer input
                committer.io.valid_num:=num
                committer.io.in_robs:= issuer.io.out_ROB


    // Architectural writeback and rename must not clear a younger producer.
    for(i<-1 until 32){
      when(issuer.io.out_prod_valid(i)){regs(i).prod:=issuer.io.out_prod(i)}
      for(lane<-0 until issueWidth){
        when(committer.io.out_reg_valid(lane) && committer.io.out_reg_id(lane)===i.U){
          regs(i).value:=committer.io.out_reg_val(lane)
          when(regs(i).prod.asUInt===committer.io.out_reg_prod(lane) && !issuer.io.out_prod_valid(i)){regs(i).prod:=(-1).S}
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
                    alus(i).aluIO.mult_low_nonzero:=mult.io.low_nonzero
                    alus(i).aluIO.mult_received:=mult.io.received
                    alus(i).aluIO.div_valid:=div.io.ready
                    alus(i).aluIO.div_res:=div.io.c
                    alus(i).aluIO.div_received:=div.io.received
    
                    mult_used(i+1):=mult_used(i)
                    div_used(i+1):=div_used(i)
                    when (alus(i).aluIO.out_mult_valid===true.B&&mult_used(i)===false.B){
                        mult.io.a:=alus(i).aluIO.out_mult_a
                        mult.io.b:=alus(i).aluIO.out_mult_b
                        mult.io.op:=alus(i).aluIO.out_mult_op
                        mult.io.valid:=(i+1).U
                        mult_used(i+1):=true.B
                    }
                    when (alus(i).aluIO.out_div_valid===true.B&&div_used(i)===false.B){
                        div.io.a:=alus(i).aluIO.out_div_a
                        div.io.b:=alus(i).aluIO.out_div_b
                        div.io.op:=alus(i).aluIO.out_div_op
                        div.io.valid:=(i+1).U
                        div_used(i+1):=true.B
                    }
                }


    // One group requests all four physical I-cache banks; retain early hits.
    when(!fetch_active && !halt && !redirect && fetcher_waiting===0.U &&
      issuer.io.out_space >= issueWidth.U + decoder.io.out_num && Icache.map(_.io.out_ready).reduce(_ && _)){
      fetch_active:=true.B
      for(bank<-0 until issueWidth){
        val offset=(bank.U(2.W)-pc.value(3,2))
        Icache(bank).io.addr:=pc.value+(offset<<2)
        Icache(bank).io.load_valid:=true.B
        fetch_done(bank):=false.B
      }
    }
    val all_hits=VecInit((0 until issueWidth).map(i=>fetch_done(i)||Icache(i).io.out_hit)).asUInt.andR
    for(i<-0 until issueWidth){
      when(fetch_active && Icache(i).io.out_hit){fetch_done(i):=true.B;fetch_data(i):=Icache(i).io.out_rdata}
    }
    when(fetch_active && all_hits && !redirect && issuer.io.out_space>=issueWidth.U+decoder.io.out_num){
      for(start<-0 until issueWidth){when(pc.value(3,2)===start.U){
        for(i<-0 until issueWidth){
          val bank=(start+i)%issueWidth
          decoder.io.id(i):=pc.value+(4*i).U
          decoder.io.opcode(i):=Mux(fetch_done(bank),fetch_data(bank),Icache(bank).io.out_rdata)
        }
      }}
      decoder.io.valid_num:=issueWidth.U
      pc.value:=pc.value+(4*issueWidth).U
      fetch_active:=false.B
    }

    // The oldest retired store owns D-cache before a new speculative load.
    when(!lsu_waiting && !committer_waiting && Dcache.io.out_ready && !flush && !io.flush_valid){
      when(committer.io.out_store_valid){
        when(committer.io.out_store_addr=/="h80000000".U){
          Dcache.io.store_valid:=true.B
          Dcache.io.addr:=committer.io.out_store_addr & "hfffffffc".U
          Dcache.io.wdata:=committer.io.out_store_value << Cat(committer.io.out_store_addr(1,0),0.U(3.W))
          Dcache.io.wmask:=committer.io.out_store_mask << committer.io.out_store_addr(1,0)
          committer_waiting:=true.B
        }
      }.elsewhen(lsu.lsuIO.out_valid){
        Dcache.io.load_valid:=true.B
        Dcache.io.addr:=lsu.lsuIO.out_addr & "hfffffffc".U
        lsu_lst_addr:=lsu.lsuIO.out_addr
        lsu_waiting:=true.B;lsu_discard:=false.B
        lsu.lsuIO.addr_received:=true.B
      }
    }
    when(Dcache.io.out_done){
      when(committer_waiting){committer.io.store_received:=true.B;committer_waiting:=false.B}
      when(lsu_waiting){
        lsu_waiting:=false.B
        when(!lsu_discard && !flush && !io.flush_valid){
          lsu.lsuIO.addr_valid:=true.B
          lsu.lsuIO.addr_value:=Dcache.io.out_rdata >> Cat(lsu_lst_addr(1,0),0.U(3.W))
        }
      }
    }

    // Shared read arbitration: D-cache misses precede instruction refill.
    when(fetcher_waiting===0.U){
      for(i<-(issueWidth-1) to 0 by -1){
        when(fetch_active && Icache(i).io.out_neednew && !redirect){
          read_addr:=Icache(i).io.out_addr;read_owner:=i.U;read_discard:=false.B;fetcher_waiting:=1.U
        }
      }
      when(Dcache.io.out_neednew){
        read_addr:=Dcache.io.out_addr;read_owner:=issueWidth.U;read_discard:=false.B;fetcher_waiting:=1.U
      }
    }.elsewhen(fetcher_waiting===1.U){
      when(io.arready){fetcher_waiting:=2.U}
    }.elsewhen(io.rvalid){
      fetcher_waiting:=0.U
      when(read_owner===issueWidth.U){
        Dcache.io.new_valid:=true.B // Finish cache work; wrong-path LSU result is suppressed separately.
      }.otherwise{
        when(!read_discard && !redirect){
          for(i<-0 until issueWidth){when(read_owner===i.U){Icache(i).io.new_valid:=true.B}}
        }
      }
    }

    // AW and W can be acknowledged in different cycles. Do not retire before B.
    when(Dcache_waiting===0.U){
      when(Dcache.io.out_wvalid){
        write_addr:=Dcache.io.out_waddr;write_data:=Dcache.io.out_wdata;write_mask:=15.U
        write_mmio:=false.B;aw_sent:=false.B;w_sent:=false.B;Dcache_waiting:=1.U
      }.elsewhen(committer.io.out_store_valid && committer.io.out_store_addr==="h80000000".U &&
        !committer_waiting && !lsu_waiting && !flush && !io.flush_valid){
        write_addr:=committer.io.out_store_addr;write_data:=committer.io.out_store_value
        write_mask:=committer.io.out_store_mask;write_mmio:=true.B
        aw_sent:=false.B;w_sent:=false.B;Dcache_waiting:=1.U
      }
    }.elsewhen(Dcache_waiting===1.U){
      when(io.awvalid && io.awready){aw_sent:=true.B}
      when(io.wvalid && io.wready){w_sent:=true.B}
      when((aw_sent || io.awready) && (w_sent || io.wready)){Dcache_waiting:=2.U}
    }.elsewhen(io.bvalid){
      Dcache_waiting:=0.U
      when(write_mmio){committer.io.store_received:=true.B;halt:=true.B}
        .otherwise{Dcache.io.store_received:=true.B}
    }

    when(redirect){
      pc.value:=redirect_addr
      fetch_active:=false.B;fetch_done.foreach(_:=false.B)
      Icache.foreach(_.io.clear_status:=true.B)
      when(fetcher_waiting=/=0.U && read_owner=/=issueWidth.U){read_discard:=true.B}
    }
    when(flush || io.flush_valid){
      cdbs.foreach(_:=0.U.asTypeOf(new CDB))
      for(i<-0 until 32){regs(i).prod:=(-1).S}
      when(lsu_waiting){lsu_discard:=true.B}
    }
}
