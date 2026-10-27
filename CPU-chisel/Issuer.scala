//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused

package Issuer
import Reg._
import CDB._
import Op._
import Config._
import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import _root_.circt.stage.ChiselStage
import Config.CPUConfig.issuerBufferWidth
import Config.CPUConfig.issueWidth

class Issuer extends Module{//needs to rewrite alu choosing!
  val io = IO(new Bundle {
    val valid_num = Input(UInt(32.W))
    val id = Input(Vec(issueWidth,UInt(32.W)))
    val cur_op = Input(Vec(issueWidth,new Op))
    val executer_space = Input(Vec(ALU_NUM+2,UInt(32.W)))
    val committer_space = Input(UInt(32.W))

    val reg= Input(Vec(32,new Reg))
    val robs = Input(Vec(ROBWidth,new ROB))

    val predictor_result = Input(Vec(issueWidth,UInt(32.W)))

    val out_executer_RS = Output(Vec(issueWidth,new RS))
    val out_executer_type = Output(Vec(issueWidth, UINt(32.W)))//0 for nothing, 1-ALU_NUM for alus, ALU_NUM+1 for LSU, ALU_NUM+2 for BRU
    val out_ROB = Output(Vec(issueWidth,new ROB))
    val out_prod = Output(Vec(32,SInt(32.W)))
    val out_prod_valid = Output(Vec(32,Bool()))
    val out_valid = Output(UInt(32.W))
    val out_new_pc_val= Output(UInt(32.W))
    val out_pc_val_changed = Output(Bool())
  })
  //the buffer queue
  val clk=RegInit(1.U(32.W))
  val nex_want=RegInit(0.U(32.W))
  clk:=clk+issueWidth.U(32.W)
  val op_buffer=Reg(Vec(issuerBufferWidth, new Op))
  val id_buffer=Reg(Vec(issuerBufferWidth,UInt(32.W)))
  val prods=Wire(Vec(32*(issueWidth+1),SInt(32.W)))
  val spaces=Wire(Vec((ALU_NUM+2)*(issueWidth+1),UInt(32.W)))
  val should_break=Wire(Vec(issueWidth+1,Bool()))
  val out_num=Wire(Vec(issueWidth+1,UInt(32.W)))
  val out_pc_val=Wire(Vec(issueWidth+1,UInt(32.W)))
  val l=RegInit(0.U(4.W))
  val r=RegInit(0.U(4.W))
  val startALU=RegInit(0.U(2.W))//the first alu to select
  val frozen = RegInit(false.B)//frozen means the issuer is waiting for the right pc_val
  io.out_pc_val_changed:=flase.B

  //add into buffer
  when ((l-r-1.U) < io.valid_num){//buffer full
   for(i<-0 until issueWidth){
    when (i.U<(l-r-1.U)){
       op_buffer(r+i.U):=io.cur_op(i)
       id_buffer(r+i.U):=io.id(i)
    }
   }
    r:=l-1.U
  }.otherwise{
    for(i<-0 until issueWidth){
    when (i.U<io.valid_num){
        op_buffer(r+i.U):=io.cur_op(i)
        id_buffer(r+i.U):=io.id(i)
    }
   }
    r:=r+io.valid_num
  }


  out_pc_val(0):=nex_want
  should_break(0):=false.B
  out_num(0):=0.U(32.W)
  for (j<-0 until ALU_NUM+2){
    spaces(j):=0.U
  }
  for (j<-0 until 32){
    prods(j):=io.reg_vals(j).prod
    io.out_prod_valid(j):=false.B
  }
//multi issuing
 val canOperate = Wire(Bool())
 val startingPoint = Wire(UInt(4.W))
 val found = Wire(Bool())
 startingPoint:=l
 canOperate := true.B
 found:=false.B
  when (frozen===true.B)
  {
    for (i<-0 until issueWidth){
        when (i.U<(l-r-1.U)&&id_buffer(l+i.U)===nex_want){
            startingPoint:=l+i.U
            found:=true.B
        }
    }
    when (found===true.B){
        frozen:=false.B
    }.otherwise{
        canOperate:=false.B
        l:=r
    }
  }
  when (canOperate===true.B)
  {
    for (i<-0 until issueWidth){
            for (j<-0 until 32){
                prods((i+1)*32+j):=prods(i*32+j)
            }
            for (j<-0 until ALU_NUM+2){
                spaces((i+1)*(ALU_NUM+2)+j):=space(i*(ALU_NUM+2)+j)
            }
            should_break(i+1):=should_break(i)
            out_num(i+1):=out_num(i)
            out_pc_val(i+1):=out_pc_val(i)
            when (should_break(i)===false.B &&i.U < (startingPoint-r-1.U)&&i.U<io.committer_space){
                when (id_buffer(startingPoint+i.U)=/=out_pc_val(i))//needs to change pc val
                {
                    io.out_pc_val_changed:=true.B
                    frozen:=true.B
                    should_break(i+1):=true.B
                }.otherwise
                {
                    val halt=Wire(UInt(2.W))//0 ok, 1 no space halt, 2 jump triggered
                    halt:=0.U
                    val rs=Wire(new RS)
                    val rob=Wire(new ROB)
                    rs.type_ := op_buffer(startingPoint+i.U).type_
                    rs.id:= clk+i.U
                    rs.q1:= (-1).S
                    rs.q2:= (-1).S
                    rs.dest:= (-1).S
                    rs.is_halt=false.B
                    rob.id:=0.U
                    rob.value:= 0.U
                    rob.dest:= 0.S
                    rob.type_ := 0.S
                    rob.ready := false.B
                    val rd=Wire(UInt(32.W))
                    val r1=Wire(UInt(32.W))
                    val r2=Wire(UInt(32.W))
                    val value=Wire(UInt(32.W))
                    val temp1=Wire(UInt(32.W))
                    val temp2=Wire(UInt(32.W))
                    val has_temp1=Wire(Bool())
                    val has_temp2=Wire(Bool())
                    val executer_type=Wire(UInt(32.W))
                    has_temp1:=false.B
                    has_temp2:=false.B
                    rd:=io.op_buffer(l+i.U).rd
                    r1:=io.op_buffer(l+i.U).r1
                    r2:=io.op_buffer(l+i.U).r2
                    for (j<-0 until ROBWidth){
                        when (io.robs(j).ready===true.B&&io.robs(j).id===prods((i*32).U+r1)){
                            temp1:=io.robs(j).value
                            has_temp1:=true.B
                        }
                        when (io.robs(j).ready===true.B&&io.robs(j).id===prods((i*32).U+r2)){
                            temp2:=io.robs(j).value
                            has_temp2:=true.B
                        }
                    }
                    value:=0.U(32.W)

                    //select alu
                    val alu_id=Wire(UInt(3.W))
                    alu_id:=ALU_NUM.U
                    for (j<-ALU_NUM-1 to 0 by -1){
                        when (spaces(i*(ALU_NUM+2)+(j.U+startALU)(1,0))<io.executer_space((j.U+startALU)(1,0))){
                            alu_id:=(j.U+startALU)(1,0)
                            startALU:=alu_id+1.U
                        }
                    }
                    switch (rs.type_){
                        is(OP_TYPE.OP_ADD, OP_TYPE.OP_SUB, OP_TYPE.OP_AND, OP_TYPE.OP_OR, OP_TYPE.OP_XOR,
                        OP_TYPE.OP_SLL, OP_TYPE.OP_SRL, OP_TYPE.OP_SRA, OP_TYPE.OP_SLT,OP_TYPE.OP_SLTU,
                        OP_TYPE.OP_MUL,OP_TYPE.OP_MULH,OP_TYPE.OP_MULHSU,OP_TYPE.OP_MULHU,OP_TYPE.OP_DIV,OP_TYPE.OP_DIVU,OP_TYPE.OP_REM,OP_TYPE.OP_REMU){
                            when (alu_id:=ALU_NUM.U){//full alu
                                halt:=1.U
                            }.otherwise{
                                when (prods((i*32).U+r1)===-1.S(32.W)){
                                    rs.v1:=reg(r1).value
                                }.otherwise{
                                    when (has_temp1===true.B){
                                        rs.v1:=temp1
                                    }.otherwise{
                                        rs.q1:=prods((i*32).U+r1)
                                    }
                                }
                                when (prods((i*32).U+r2)===-1.S(32.W)){
                                    rs.v2:=reg(r2).value
                                }.otherwise{
                                    when (has_temp2===true.B){
                                        rs.v2:=temp2
                                    }.otherwise{
                                        rs.q2:=prods((i*32).U+r2)
                                    }
                                }
                                rs.dest:=rd
                                prods(((i+1)*32).U+rd):=clk+i.U
                                io.out_prod_valid(rd):=true.B
                                executer_type:=alu_id
                                rob.id:=clk+i.U
                                rob.dest:=rd
                                out_pc_val(i+1):=out_pc_val(i)+4.U
                            }
                        }
                        is(OP_TYPE.OP_ADDI, OP_TYPE.OP_ANDI, OP_TYPE.OP_ORI, OP_TYPE.OP_XORI, OP_TYPE.OP_SLLI,
                        OP_TYPE.OP_SRLI, OP_TYPE.OP_SRAI, OP_TYPE.OP_SLTI,OP_TYPE.OP_SLTUI){
                            when (alu_id:=ALU_NUM.U){//full alu
                                halt:=1.U
                            }.otherwise{
                                when (prods((i*32).U+r1)===-1.S(32.W)){
                                    rs.v1:=reg(r1).value
                                }.otherwise{
                                    when (has_temp1===true.B){
                                        rs.v1:=temp1
                                    }.otherwise{
                                        rs.q1:=prods((i*32).U+r1)
                                    }
                                }
                                rs.v2:=r2
                                rs.dest:=rd
                                rs.is_halt:=(rs.type_ ===OP_TYPE.OP_ADDI&&r1===0.U&&r2===255.U&&rd===10.U)//halt instruction
                                prods(((i+1)*32).U+rd):=clk+i.U
                                io.out_prod_valid(rd):=true.B
                                executer_type:=alu_id
                                rob.id:=clk+i.U
                                rob.dest:=rd
                                out_pc_val(i+1):=out_pc_val(i)+4.U
                            }
                        }
                        is(OP_TYPE.OP_LB, OP_TYPE.OP_LH, OP_TYPE.OP_LHU, OP_TYPE.OP_LW){
                            when (spaces(i*(ALU_NUM+2)+ALU_NUM)===executer_space(ALU_NUM)){
                                halt:=1.U
                            }.otherwise{
                                when (prods((i*32).U+r1)===-1.S(32.W)){
                                    rs.v1:=reg(r1).value
                                }.otherwise{
                                    when (has_temp1===true.B){
                                        rs.v1:=temp1
                                    }.otherwise{
                                        rs.q1:=prods((i*32).U+r1)
                                    }
                                }
                                rs.v2:=r2
                                rs.dest:=rd
                                prods(((i+1)*32).U+rd):=clk+i.U
                                io.out_prod_valid(rd):=true.B
                                executer_type:=ALU_NUM.U//lsu
                                rob.id:=clk+i.U
                                rob.dest:=rd
                                out_pc_val(i+1):=out_pc_val(i)+4.U
                            }
                        }
                        is(OP_TYPE.OP_SB, OP_TYPE.OP_SH, OP_TYPE.OP_SW){
                            when (spaces(i*(ALU_NUM+2)+ALU_NUM)===executer_space(ALU_NUM)){
                                halt:=1.U
                            }.otherwise{
                                when (prods((i*32).U+r1)===-1.S(32.W)){
                                    rs.v1:=reg(r1).value
                                }.otherwise{
                                    when (has_temp1===true.B){
                                        rs.v1:=temp1
                                    }.otherwise{
                                        rs.q1:=prods((i*32).U+r1)
                                    }
                                }
                                when (prods((i*32).U+r2)===-1.S(32.W)){
                                    rs.v2:=reg(r2).value
                                }.otherwise{
                                    when (has_temp2===true.B){
                                        rs.v2:=temp2
                                    }.otherwise{
                                        rs.q2:=prods((i*32).U+r2)
                                    }
                                }
                                rs.dest:=rd
                                executer_type:=ALU_NUM.U//lsu
                                rob.id:=clk+i.U
                                rob.type_ := MUX(rs.type_ === OP_TYPE.OP_SB,1.U,MUX(rs.type_ === OP_TYPE.OP_SH,2,4))
                                out_pc_val(i+1):=out_pc_val(i)+4.U
                            }
                        }
                        is(OP_TYPE.OP_BEQ, OP_TYPE.OP_BGE, OP_TYPE.OP_BGEU,OP_TYPE.OP_BLT, OP_TYPE.OP_BLTU, OP_TYPE.OP_BNE){
                            when (spaces(i*(ALU_NUM+2)+ALU_NUM+1)===executer_space(ALU_NUM+1)){//bru_full
                                halt:=1.U
                            }.otherwise{
                                when (prods((i*32).U+r1)===-1.S(32.W)){
                                    rs.v1:=reg(r1).value
                                }.otherwise{
                                    when (has_temp1===true.B){
                                        rs.v1:=temp1
                                    }.otherwise{
                                        rs.q1:=prods((i*32).U+r1)
                                    }
                                }
                                when (prods((i*32).U+r2)===-1.S(32.W)){
                                    rs.v2:=reg(r2).value
                                }.otherwise{
                                    when (has_temp2===true.B){
                                        rs.v2:=temp2
                                    }.otherwise{
                                        rs.q2:=prods((i*32).U+r2)
                                    }
                                }
                                rs.dest:=io.predictor_result(i)(0)
                                executer_type:=(ALU_NUM+1).U//bru
                                rob.id:=clk+i.U
                                rob.dest:=MUX(rs.dest=/=0.U(32.W),id_buffer(startingPoint+i.U)+4.U,(id_buffer(startingPoint+i.U).asSInt+rd(12, 0).asSInt).asUInt)
                                rob.type_ := (~id_buffer(startingPoint+i.U)).asSInt
                                rob.value:=io.predictor_result(i)
                                out_pc_val(i+1):=MUX(rs.dest=/=0.U(32.W),(out_pc_val(i).asSInt+rd(12,0).asSInt).asUInt,out_pc_val(i)+4.U)
                                halt:=2.U
                            }
                        }
                        is(OP_TYPE.JAL){
                            when (alu_id:=ALU_NUM.U){//full alu
                                halt:=1.U
                            }.otherwise{
                                rs.v1:=id_buffer(startingPoint+i.U)+4.U
                                rs.dest:=rd
                                prods(((i+1)*32).U+rd):=clk+i.U
                                io.out_prod_valid(rd):=true.B
                                executer_type:=alu_id
                                rob.id:=clk+i.U
                                rob.dest:=rd
                                out_pc_val(i+1):=(out_pc_val(i).asSInt+r1(20,0).asSInt).asUInt
                                halt:=2.U
                            }
                        }
                        is(OP_TYPE.JALR){
                            when (alu_id:=ALU_NUM.U||(rd=/=r1&&prods((i*32).U+r1)=/=(-1).S&&has_temp1===false.B)){//full alu or not clear rs1
                                halt:=1.U
                            }.otherwise{
                                rs.v1:=id_buffer(startingPoint+i.U)+4.U
                                rs.dest:=rd
                                prods(((i+1)*32).U+rd):=clk+i.U
                                io.out_prod_valid(rd):=true.B
                                executer_type:=alu_id
                                rob.id:=clk+i.U
                                rob.dest:=rd
                                when (rd===r1)
                                {
                                    out_pc_val(i+1):=(out_pc_val(i).asSInt+4.S+r2(11,0).asSInt).asUInt
                                }.elsewhen (prods((i*32).U+r1)===-1.S(32.W))
                                {
                                    out_pc_val(i+1):=(out_pc_val(i).asSInt+reg(r1).value.asSInt+r2(11,0).asSInt-id_buffer(startingPoint+i.U).asSInt).asUInte
                                }.otherwise
                                {
                                    out_pc_val(i+1):=(out_pc_val(i).asSInt+temp1.asSInt+r2(11,0).asSInt-id_buffer(startingPoint+i.U).asSInt).asUInte
                                }
                                halt:=2.U
                            }
                        }
                        is(OP_TYPE.AUIPC){
                            when (alu_id:=ALU_NUM.U){//full alu
                                halt:=1.U
                            }.otherwise{
                                rs.v1:=id_buffer(startingPoint+i.U)+r1
                                rs.dest:=rd
                                prods(((i+1)*32).U+rd):=clk+i.U
                                io.out_prod_valid(rd):=true.B
                                executer_type:=alu_id
                                rob.id:=clk+i.U
                                rob.dest:=rd
                                out_pc_val(i+1):=out_pc_val(i)+4.U
                            }
                        }
                        is(OP_TYPE.LUI){
                            when (alu_id:=ALU_NUM.U){//full alu
                                halt:=1.U
                            }.otherwise{
                                rs.v1:=r1
                                rs.dest:=rd
                                prods(((i+1)*32).U+rd):=clk+i.U
                                io.out_prod_valid(rd):=true.B
                                executer_type:=alu_id
                                rob.id:=clk+i.U
                                rob.dest:=rd
                                out_pc_val(i+1):=out_pc_val(i)+4.U
                            }
                        }
                    }
                    io.out_executer_type(i):=executer_type
                    io.out_executer_RS(i):=rs
                    io.out_ROB(i):=rob
                    when (halt=/=1.U){
                        spaces((i+1)*(ALU_NUM+2)+executer_type):=spaces(i*(ALU_NUM+2)+executer_type)+1.U
                        out_num(i+1):=(i+1).U
                    }
                    when (halt=/=0.U){
                        should_break(i+1):=true.B
                    }
                }
            }
        }
        //output and update
        io.out_valid:=out_num(issueWidth)
        l:=startingPoint+out_num(issueWidth)
        io.out_new_pc_val:=out_pc_val(issueWidth)
        nex_want:=out_pc_val(issueWidth)
        for (i<-0 until 32){
            io.out_prod(i):=prods(32*issueWidth+i)
        }
    }
}
