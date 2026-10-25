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

class CPU extends Module{
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
    val reg = RegInit(VecInit(Seq.fill(32)(init)))
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
                when (reg(i).prod>flush_val){
                    reg(i).prod:=-1.S(32.W)
                }
            }
            committer.io.clear_valid:=true.B
            decoder.io.valid_num:=0.U(32.W)
            issuer.io.valid_num:=0.U(32.W)
            flush:=false.B
        }.otherwise{//main cycle
            //fetcher
            decoder.io.valid_num:=0.U(32.W)
            issuer.io.valid_num:=0.U(32.W)
            when(fetcher_waiting===0.U(2.W)){
        
                    for (i<-0 until issueWidth){
                        decoder.io.id(i):=Icache.io.output_id(i)
                        decoder.io.opcode(i):=Icache.io.output_value(i)
                    }
                    Icache_out_num := Icache.io.out_num
                    decoder.io.valid_num:= Icache_out_num
                    when (Icache_out_num===0.U(32.W)){
                        io.araddr:=pc.value
                        io.arvalid:=true.B
                        fetcher_waiting:=1.U(2.W)
                    }
                }.elsewhen(fetcher_waiting===1.U(2.W)){
                    when (io.arready===true.B){
                        fetcher_waiting:=2.U(2.W)
                        io.arready:=true.B
                    }.otherwise{
                        io.araddr:=pc.value
                        io.arvalid:=true.B
                    }
                }.elsewhen(fetcher_waiting==2.U(2.W)){
                    when (io.rvalid===true.B){
                        fetcher_waiting:=0.U(2.W)
                        decoder.io.valid_num:=1.U(32.W)
                        decoder.io.id(0):=pc.value
                        decoder.io.opcode(0):=io.rdata
                    }.otherwise{
                        io.arready:=true.B
                    }
                }
                //handling issuer input
                issuer.io.valid_num:=decoder.out_num
                for (i<-0 until issueWidth){
                    issuer.io.id(i):=decoder.io.out_id(i)
                    issuer.io.cur_op(i):=decoder.io.out_op(i)
                }
                for (i<-0 until 32){
                    issuer.io.reg(i):=reg(i)
                }
                for (i<-0 until ALU_NUM){
                    issuer.io.executer_space(i):=alus(i).io.out_space
                }
                issuer.io.robs:=committer.io.out_read_rob
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
                committer.io.robs:= issuer.io.out_rob

                //updating register prods
                for (i<-0 until 32){
                    reg(i).prod:=issuer.io.out_prod(i)
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

        }
    }.otherwise{//halt
        io.awaddr:=0x80000000.U(32.W)
        io.wstrb:=15.U(4.W)
        io.wdata:=regs(10).value
        io.wvalid:=true.B
        io.awvalid:=true.B
    }
}

void broadcast(CDB cdb)
{
    if (cdb.id == -1)
    {
        return;
    }
    alu.receive(cdb);
    lsu.receive(cdb);
    bru.receive(cdb);
    committer.receive(cdb);
}

int main()
{
        // fetch
        pair<int, unsigned int> fetcher_output = make_pair(pc.val, memory.fetch(pc.val));
        // decode
        pair<int, op> decode_output = decoder.decode();
        // issue
        int new_pc_val = issuer.issue(clk);
        // execute
        CDB alu_cdb = alu.execute();
        CDB lsu_cdb = lsu.execute();
        CDB bru_cdb = bru.execute();
        // commit
        pair<int, int> committer_output = committer.Commit();
        int res = committer_output.first;
        if (res < 0)
        { // revert
            flush_val = -res;
            newaddr = committer_output.second;
        }
        // update
        decoder.id = fetcher_output.first;
        decoder.opcode = fetcher_output.second;
        issuer.id = decode_output.first;
        issuer.cur_op = decode_output.second;
        pc.val = new_pc_val;
        broadcast(alu_cdb);
        broadcast(lsu_cdb);
        broadcast(bru_cdb);
        broadcast(pre_alu_cdb);
        broadcast(pre_lsu_cdb);
        broadcast(pre_bru_cdb);
        pre_alu_cdb = alu_cdb;
        pre_lsu_cdb = lsu_cdb;
        pre_bru_cdb = bru_cdb;
        alu.update();
        lsu.update();
        bru.update();
        committer.update();
        predictor.update();
        memory.update();
    }
    // cout<<"clock="<<clk<<endl;
    // cout<<"hit= "<<memory.hit<<" not hit= "<<memory.nothit<<" accuracy= "<<(double)memory.hit/(memory.hit+memory.nothit)<<endl;
    // cout<<"branch predictor accuracy="<<(double)committer.right/committer.total<<endl;
    return 0;
}