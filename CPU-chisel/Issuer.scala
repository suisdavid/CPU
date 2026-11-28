//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
// Unused imports in the draft are not compilation errors.

package Issuer
import Register._
import RS._
import ROB._
import CDB._
import Op._
import Config.CPUConfig._
import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.RawTester.test
import _root_.circt.stage.ChiselStage
import Config.CPUConfig.issuerBufferWidth
import Config.CPUConfig.issueWidth

class Issuer extends Module{
  val io = IO(new Bundle {
    val clear_valid = Input(Bool())
    val clear_pc = Input(UInt(32.W))
    val cdbs = Input(Vec(ALU_NUM+2,new CDB))
    val out_space = Output(UInt(32.W))
    val valid_num = Input(UInt(32.W))
    val id = Input(Vec(issueWidth,UInt(32.W)))
    val cur_op = Input(Vec(issueWidth,new Op))
    val executer_space = Input(Vec(ALU_NUM+2,UInt(32.W)))
    val committer_space = Input(UInt(32.W))

    val reg= Input(Vec(32,new Register))
    val robs = Input(Vec(ROBWidth,new ROB))

    val predictor_result = Input(Vec(issueWidth,UInt(32.W)))

    val out_executer_RS = Output(Vec(issueWidth,new RS))
    val out_executer_type = Output(Vec(issueWidth, UInt(32.W)))//0 for nothing, 1-ALU_NUM for alus, ALU_NUM+1 for LSU, ALU_NUM+2 for BRU
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
  io.out_pc_val_changed:=false.B
  io.out_space := (issuerBufferWidth-1).U - (r-l)
  io.out_valid:=0.U
  io.out_new_pc_val:=nex_want
  val predictor_buffer=Reg(Vec(issuerBufferWidth,UInt(32.W)))
  for(i<-0 until issueWidth){
    io.out_executer_RS(i):=0.U.asTypeOf(new RS)
    io.out_executer_type(i):=0.U
    io.out_ROB(i):=0.U.asTypeOf(new ROB)
    for(j<-0 until 32){prods((i+1)*32+j):=prods(i*32+j)}
    for(j<-0 until ALU_NUM+2){spaces((i+1)*(ALU_NUM+2)+j):=spaces(i*(ALU_NUM+2)+j)}
    should_break(i+1):=should_break(i)
    out_num(i+1):=out_num(i)
    out_pc_val(i+1):=out_pc_val(i)
  }
  for(j<-0 until 32){ io.out_prod(j):=io.reg(j).prod }


  //add into buffer
  when (io.out_space < io.valid_num){//buffer full
   for(i<-0 until issueWidth){
    when (i.U<io.out_space){
       op_buffer(ringIndex(r,i.U)):=io.cur_op(i)
       id_buffer(ringIndex(r,i.U)):=io.id(i)
        predictor_buffer(ringIndex(r,i.U)):=io.predictor_result(i)
    }
   }
    r:=l-1.U
  }.otherwise{
    for(i<-0 until issueWidth){
    when (i.U<io.valid_num){
        op_buffer(ringIndex(r,i.U)):=io.cur_op(i)
        id_buffer(ringIndex(r,i.U)):=io.id(i)
        predictor_buffer(ringIndex(r,i.U)):=io.predictor_result(i)
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
    prods(j):=Mux(j.U===0.U,(-1).S,io.reg(j).prod)
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
        when (i.U<(r-l)&&id_buffer(ringIndex(l,i.U))===nex_want){
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
                spaces((i+1)*(ALU_NUM+2)+j):=spaces(i*(ALU_NUM+2)+j)
            }
            should_break(i+1):=should_break(i)
            out_num(i+1):=out_num(i)
            out_pc_val(i+1):=out_pc_val(i)
            when (should_break(i)===false.B &&i.U < (r-startingPoint)&&i.U<io.committer_space){
                when (id_buffer(ringIndex(startingPoint,i.U))=/=out_pc_val(i))//needs to change pc val
                {
                    io.out_pc_val_changed:=true.B
                    frozen:=true.B
                    should_break(i+1):=true.B
                }.otherwise
                {
                    val halt=Wire(UInt(2.W))//0 ok, 1 no space halt, 2 jump triggered
                    halt:=0.U
                    val rs=WireDefault(0.U.asTypeOf(new RS))
                    val rob=Wire(new ROB)
                    rs.type_ := op_buffer(ringIndex(startingPoint,i.U)).type_
                    rs.id:= clk+i.U
                    rs.q1:= (-1).S
                    rs.q2:= (-1).S
                    rs.dest:= (-1).S
                    rs.is_halt:=false.B
                    rob.id:=0.U
                    rob.value:= 0.U
                    rob.dest:= 0.S
                    rob.type_ := 0.S
                    rob.ready := false.B
                    val rd=Wire(UInt(32.W))
                    val r1=Wire(UInt(32.W))
                    val r2=Wire(UInt(32.W))
                    val value=Wire(UInt(32.W))
                    val temp1=WireDefault(0.U(32.W))
                    val temp2=WireDefault(0.U(32.W))
                    val has_temp1=Wire(Bool())
                    val has_temp2=Wire(Bool())
                    val executer_type=WireDefault(0.U(32.W))
                    has_temp1:=false.B
                    has_temp2:=false.B
                    rd:=op_buffer(ringIndex(startingPoint,i.U)).rd
                    r1:=op_buffer(ringIndex(startingPoint,i.U)).r1
                    r2:=op_buffer(ringIndex(startingPoint,i.U)).r2
                    val lane_prods=VecInit((0 until 32).map(j=>prods(i*32+j)))
                    val lane_spaces=VecInit((0 until ALU_NUM+2).map(j=>spaces(i*(ALU_NUM+2)+j)))
                    for(j<-0 until ALU_NUM+2){
                      when(io.cdbs(j).valid && io.cdbs(j).id.asSInt===lane_prods(r1(4,0))){temp1:=io.cdbs(j).value;has_temp1:=true.B}
                      when(io.cdbs(j).valid && io.cdbs(j).id.asSInt===lane_prods(r2(4,0))){temp2:=io.cdbs(j).value;has_temp2:=true.B}
                    }
                    for (j<-0 until ROBWidth){
                        when (io.robs(j).ready===true.B&&io.robs(j).id.asSInt===lane_prods(r1(4,0))){
                            temp1:=io.robs(j).value
                            has_temp1:=true.B
                        }
                        when (io.robs(j).ready===true.B&&io.robs(j).id.asSInt===lane_prods(r2(4,0))){
                            temp2:=io.robs(j).value
                            has_temp2:=true.B
                        }
                    }
                    value:=0.U(32.W)

                    //select alu
                    val alu_id=Wire(UInt(3.W))
                    alu_id:=ALU_NUM.U
                    for (j<-ALU_NUM-1 to 0 by -1){
                        when (lane_spaces((j.U+startALU)(1,0).pad(3))<io.executer_space((j.U+startALU)(1,0).pad(3))){
                            alu_id:=(j.U+startALU)(1,0)
                            startALU:=alu_id+1.U
                        }
                    }
                    switch (rs.type_){
                        is(OP_TYPE.OP_NONE){halt:=1.U}
                        is(OP_TYPE.OP_ADD, OP_TYPE.OP_SUB, OP_TYPE.OP_AND, OP_TYPE.OP_OR, OP_TYPE.OP_XOR,
                        OP_TYPE.OP_SLL, OP_TYPE.OP_SRL, OP_TYPE.OP_SRA, OP_TYPE.OP_SLT,OP_TYPE.OP_SLTU,
                        OP_TYPE.OP_MUL,OP_TYPE.OP_MULH,OP_TYPE.OP_MULHSU,OP_TYPE.OP_MULHU,OP_TYPE.OP_DIV,OP_TYPE.OP_DIVU,OP_TYPE.OP_REM,OP_TYPE.OP_REMU){
                            when (alu_id===ALU_NUM.U){//full alu
                                halt:=1.U
                            }.otherwise{
                                when (lane_prods(r1(4,0))=== (-1).S(32.W)){
                                    rs.v1:=io.reg(r1(4,0)).value
                                }.otherwise{
                                    when (has_temp1===true.B){
                                        rs.v1:=temp1
                                    }.otherwise{
                                        rs.q1:=lane_prods(r1(4,0))
                                    }
                                }
                                when (lane_prods(r2(4,0))=== (-1).S(32.W)){
                                    rs.v2:=io.reg(r2(4,0)).value
                                }.otherwise{
                                    when (has_temp2===true.B){
                                        rs.v2:=temp2
                                    }.otherwise{
                                        rs.q2:=lane_prods(r2(4,0))
                                    }
                                }
                                rs.dest:=rd.asSInt
                                for(j<-1 until 32){ when(rd===j.U){ prods((i+1)*32+j):=(clk+i.U).asSInt;io.out_prod_valid(j):=true.B } }

                                executer_type:=alu_id
                                rob.id:=clk+i.U
                                rob.dest:=rd.asSInt
                                out_pc_val(i+1):=out_pc_val(i)+4.U
                            }
                        }
                        is(OP_TYPE.OP_ADDI, OP_TYPE.OP_ANDI, OP_TYPE.OP_ORI, OP_TYPE.OP_XORI, OP_TYPE.OP_SLLI,
                        OP_TYPE.OP_SRLI, OP_TYPE.OP_SRAI, OP_TYPE.OP_SLTI,OP_TYPE.OP_SLTIU){
                            when (alu_id===ALU_NUM.U){//full alu
                                halt:=1.U
                            }.otherwise{
                                when (lane_prods(r1(4,0))=== (-1).S(32.W)){
                                    rs.v1:=io.reg(r1(4,0)).value
                                }.otherwise{
                                    when (has_temp1===true.B){
                                        rs.v1:=temp1
                                    }.otherwise{
                                        rs.q1:=lane_prods(r1(4,0))
                                    }
                                }
                                rs.v2:=r2
                                rs.dest:=rd.asSInt
                                rs.is_halt:=false.B // ADDI a0,x0,255 is an ordinary instruction.
                                for(j<-1 until 32){ when(rd===j.U){ prods((i+1)*32+j):=(clk+i.U).asSInt;io.out_prod_valid(j):=true.B } }

                                executer_type:=alu_id
                                rob.id:=clk+i.U
                                rob.dest:=rd.asSInt
                                out_pc_val(i+1):=out_pc_val(i)+4.U
                            }
                        }
                        is(OP_TYPE.OP_LB, OP_TYPE.OP_LBU, OP_TYPE.OP_LH, OP_TYPE.OP_LHU, OP_TYPE.OP_LW){
                            when (spaces(i*(ALU_NUM+2)+ALU_NUM)===io.executer_space(ALU_NUM)){
                                halt:=1.U
                            }.otherwise{
                                when (lane_prods(r1(4,0))=== (-1).S(32.W)){
                                    rs.v1:=io.reg(r1(4,0)).value
                                }.otherwise{
                                    when (has_temp1===true.B){
                                        rs.v1:=temp1
                                    }.otherwise{
                                        rs.q1:=lane_prods(r1(4,0))
                                    }
                                }
                                rs.v2:=r2
                                rs.dest:=rd.asSInt
                                for(j<-1 until 32){ when(rd===j.U){ prods((i+1)*32+j):=(clk+i.U).asSInt;io.out_prod_valid(j):=true.B } }

                                executer_type:=ALU_NUM.U//lsu
                                rob.id:=clk+i.U
                                rob.dest:=rd.asSInt
                                out_pc_val(i+1):=out_pc_val(i)+4.U
                            }
                        }
                        is(OP_TYPE.OP_SB, OP_TYPE.OP_SH, OP_TYPE.OP_SW){
                            when (spaces(i*(ALU_NUM+2)+ALU_NUM)===io.executer_space(ALU_NUM)){
                                halt:=1.U
                            }.otherwise{
                                when (lane_prods(r1(4,0))=== (-1).S(32.W)){
                                    rs.v1:=io.reg(r1(4,0)).value
                                }.otherwise{
                                    when (has_temp1===true.B){
                                        rs.v1:=temp1
                                    }.otherwise{
                                        rs.q1:=lane_prods(r1(4,0))
                                    }
                                }
                                when (lane_prods(r2(4,0))=== (-1).S(32.W)){
                                    rs.v2:=io.reg(r2(4,0)).value
                                }.otherwise{
                                    when (has_temp2===true.B){
                                        rs.v2:=temp2
                                    }.otherwise{
                                        rs.q2:=lane_prods(r2(4,0))
                                    }
                                }
                                rs.dest:=rd.asSInt
                                executer_type:=ALU_NUM.U//lsu
                                rob.id:=clk+i.U
                                rob.type_ := Mux(rs.type_ === OP_TYPE.OP_SB,1.S,Mux(rs.type_ === OP_TYPE.OP_SH,2.S,4.S))
                                out_pc_val(i+1):=out_pc_val(i)+4.U
                            }
                        }
                        is(OP_TYPE.OP_BEQ, OP_TYPE.OP_BGE, OP_TYPE.OP_BGEU,OP_TYPE.OP_BLT, OP_TYPE.OP_BLTU, OP_TYPE.OP_BNE){
                            when (spaces(i*(ALU_NUM+2)+ALU_NUM+1)===io.executer_space(ALU_NUM+1)){//bru_full
                                halt:=1.U
                            }.otherwise{
                                when (lane_prods(r1(4,0))=== (-1).S(32.W)){
                                    rs.v1:=io.reg(r1(4,0)).value
                                }.otherwise{
                                    when (has_temp1===true.B){
                                        rs.v1:=temp1
                                    }.otherwise{
                                        rs.q1:=lane_prods(r1(4,0))
                                    }
                                }
                                when (lane_prods(r2(4,0))=== (-1).S(32.W)){
                                    rs.v2:=io.reg(r2(4,0)).value
                                }.otherwise{
                                    when (has_temp2===true.B){
                                        rs.v2:=temp2
                                    }.otherwise{
                                        rs.q2:=lane_prods(r2(4,0))
                                    }
                                }
                                rs.dest:=predictor_buffer(ringIndex(startingPoint,i.U))(0).asUInt.zext
                                executer_type:=(ALU_NUM+1).U//bru
                                rob.id:=clk+i.U
                                rob.dest:=Mux(rs.dest=/=0.S,id_buffer(ringIndex(startingPoint,i.U))+4.U,(id_buffer(ringIndex(startingPoint,i.U)).asSInt+rd(12, 0).asSInt).asUInt).asSInt
                                rob.type_ := (~id_buffer(ringIndex(startingPoint,i.U))).asSInt
                                rob.value:=predictor_buffer(ringIndex(startingPoint,i.U))
                                out_pc_val(i+1):=Mux(rs.dest=/=0.S,(out_pc_val(i).asSInt+rd(12,0).asSInt).asUInt,out_pc_val(i)+4.U)
                                halt := Mux(rs.dest =/= 0.S, 2.U, 0.U)// if doesn't jump by prediction, no need to halt
                            }
                        }
                        is(OP_TYPE.OP_JAL){
                            when (alu_id===ALU_NUM.U){//full alu
                                halt:=1.U
                            }.otherwise{
                                rs.v1:=id_buffer(ringIndex(startingPoint,i.U))+4.U
                                rs.dest:=rd.asSInt
                                for(j<-1 until 32){ when(rd===j.U){ prods((i+1)*32+j):=(clk+i.U).asSInt;io.out_prod_valid(j):=true.B } }

                                executer_type:=alu_id
                                rob.id:=clk+i.U
                                rob.dest:=rd.asSInt
                                out_pc_val(i+1):=(out_pc_val(i).asSInt+r1(20,0).asSInt).asUInt
                                halt:=2.U
                            }
                        }
                        is(OP_TYPE.OP_JALR){
                            when (alu_id===ALU_NUM.U||(lane_prods(r1(4,0))=/=(-1).S&&has_temp1===false.B)){//full alu or not clear rs1
                                halt:=1.U
                            }.otherwise{
                                rs.v1:=id_buffer(ringIndex(startingPoint,i.U))+4.U
                                rs.dest:=rd.asSInt
                                for(j<-1 until 32){ when(rd===j.U){ prods((i+1)*32+j):=(clk+i.U).asSInt;io.out_prod_valid(j):=true.B } }

                                executer_type:=alu_id
                                rob.id:=clk+i.U
                                rob.dest:=rd.asSInt
                                val base=Mux(lane_prods(r1(4,0))===(-1).S,io.reg(r1(4,0)).value,temp1)
                                out_pc_val(i+1):=(base+r2(11,0).asSInt.pad(32).asUInt)&"hfffffffe".U
                                halt:=2.U
                            }
                        }
                        is(OP_TYPE.OP_AUIPC){
                            when (alu_id===ALU_NUM.U){//full alu
                                halt:=1.U
                            }.otherwise{
                                rs.v1:=id_buffer(ringIndex(startingPoint,i.U))+r1
                                rs.dest:=rd.asSInt
                                for(j<-1 until 32){ when(rd===j.U){ prods((i+1)*32+j):=(clk+i.U).asSInt;io.out_prod_valid(j):=true.B } }

                                executer_type:=alu_id
                                rob.id:=clk+i.U
                                rob.dest:=rd.asSInt
                                out_pc_val(i+1):=out_pc_val(i)+4.U
                            }
                        }
                        is(OP_TYPE.OP_LUI){
                            when (alu_id===ALU_NUM.U){//full alu
                                halt:=1.U
                            }.otherwise{
                                rs.v1:=r1
                                rs.dest:=rd.asSInt
                                for(j<-1 until 32){ when(rd===j.U){ prods((i+1)*32+j):=(clk+i.U).asSInt;io.out_prod_valid(j):=true.B } }

                                executer_type:=alu_id
                                rob.id:=clk+i.U
                                rob.dest:=rd.asSInt
                                out_pc_val(i+1):=out_pc_val(i)+4.U
                            }
                        }
                    }
                    io.out_executer_type(i):=executer_type
                    io.out_executer_RS(i):=rs
                    io.out_ROB(i):=rob
                    when (halt=/=1.U){
                        for(j<-0 until ALU_NUM+2){ when(executer_type===j.U){spaces((i+1)*(ALU_NUM+2)+j):=spaces(i*(ALU_NUM+2)+j)+1.U} }
                        out_num(i+1):=(i+1).U
                    }
                    when(halt===2.U){io.out_pc_val_changed:=true.B;frozen:=true.B}
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
    when(io.clear_valid){
      l:=0.U;r:=0.U;frozen:=false.B;nex_want:=io.clear_pc
      io.out_valid:=0.U;io.out_pc_val_changed:=false.B
      io.out_prod_valid.foreach(_:=false.B)
    }

}
