//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
// Unused imports in the draft are not compilation errors.
package Executer

import Op._
import RS._
import ROB._
import CDB._
import Config.CPUConfig._
import chisel3._
import chisel3.util._
import chiseltest._
import chiseltest.RawTester.test
import _root_.circt.stage.ChiselStage

class Adder extends Module {
  val io = IO(new Bundle {
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(32.W))
    val sum = Output(UInt(32.W))
    val cout = Output(Bool())
  })
  val p= Wire(Vec(63,Bool()))
  val g= Wire(Vec(63,Bool()))
  val c= Wire(Vec(32,Bool()))
  for (i<-0 until 32){
    p(i):=io.a(i)^io.b(i)
    g(i):=io.a(i)&io.b(i)
  }
  var offset1=32
  var offset2=0
  for (w<-4 to 0 by -1){
    for (i<-0 until (1<<w)){
      p(i+offset1):=p(i*2+offset2)&p(i*2+offset2+1)
      g(i+offset1):=g(i*2+offset2+1)|(p(i*2+offset2+1)&g(i*2+offset2))
    }
    offset2=offset1
    offset1+=(1<<w)

  }
  var offset=62
  c(0):=false.B
  for (w<-4 to 0 by -1){
    for (i<-(32-(1<<(w+1))) to 0 by -(1<<(w+1))){
      offset-=2
      c(i+(1<<w)):=g(offset)|(p(offset)&c(i))
    }
  }
  val pBits = p.asUInt
  val cBits = c.asUInt
  io.cout := g(62)
  io.sum := pBits(31, 0) ^ cBits  
}

class LShifter extends Module{
    val io = IO(new Bundle {
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(5.W))
    val c = Output(UInt(32.W))
  })
  val a1,a2,a3,a4=Wire(UInt(32.W))
  a1:=Mux(io.b(0),Cat(io.a(30,0),0.U(1.W)),io.a)
  a2:=Mux(io.b(1),Cat(a1(29,0),0.U(2.W)),a1)
  a3:=Mux(io.b(2),Cat(a2(27,0),0.U(4.W)),a2)
  a4:=Mux(io.b(3),Cat(a3(23,0),0.U(8.W)),a3)
  io.c:=Mux(io.b(4),Cat(a4(15,0),0.U(16.W)),a4)
}

class RShifter extends Module{
    val io = IO(new Bundle {
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(5.W))
    val signed = Input(Bool())
    val c = Output(UInt(32.W))
  })
  val a1,a2,a3,a4=Wire(UInt(32.W))
  when (io.signed&io.a(31))
  {
        a1:=Mux(io.b(0),Cat(1.U(1.W),io.a(31,1)),io.a)
        a2:=Mux(io.b(1),Cat(3.U(2.W),a1(31,2)),a1)
        a3:=Mux(io.b(2),Cat(15.U(4.W),a2(31,4)),a2)
        a4:=Mux(io.b(3),Cat(255.U(8.W),a3(31,8)),a3)
        io.c:=Mux(io.b(4),Cat(65535.U(16.W),a4(31,16)),a4)
  }.otherwise{
        a1:=Mux(io.b(0),Cat(0.U(1.W),io.a(31,1)),io.a)
        a2:=Mux(io.b(1),Cat(0.U(2.W),a1(31,2)),a1)
        a3:=Mux(io.b(2),Cat(0.U(4.W),a2(31,4)),a2)
        a4:=Mux(io.b(3),Cat(0.U(8.W),a3(31,8)),a3)
        io.c:=Mux(io.b(4),Cat(0.U(16.W),a4(31,16)),a4)
  }
}

class Subtracter extends Module{
  val io = IO(new Bundle {
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(32.W))
    val c = Output(UInt(32.W))
  })
  val p= Wire(Vec(63,Bool()))
  val g= Wire(Vec(63,Bool()))
  val c= Wire(Vec(32,Bool()))
  for (i<-0 until 32){
    p(i):=io.a(i)^(!io.b(i))
    g(i):=io.a(i)&(!io.b(i))
  }
  var offset1=32
  var offset2=0
  for (w<-4 to 0 by -1){
    for (i<-0 until (1<<w)){
      p(i+offset1):=p(i*2+offset2)&p(i*2+offset2+1)
      g(i+offset1):=g(i*2+offset2+1)|(p(i*2+offset2+1)&g(i*2+offset2))
    }
    offset2=offset1
    offset1+=(1<<w)

  }
  var offset=62
  c(0):=true.B
  for (w<-4 to 0 by -1){
    for (i<-(32-(1<<(w+1))) to 0 by -(1<<(w+1))){
      offset-=2
      c(i+(1<<w)):=g(offset)|(p(offset)&c(i))
    }
  }
  val pBits = p.asUInt
  val cBits = c.asUInt
  io.c := pBits(31, 0) ^ cBits  
}


class Comparer extends Module{//returns 1 if a>=b
    val io = IO(new Bundle {
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(32.W))
    val signed = Input(Bool())
    val c = Output(Bool())
  })
  val eq = (io.a===io.b)
  val reverted = io.signed&io.a(31)
  val sub=Module(new Subtracter)
  sub.io.a:=io.a
  sub.io.b:=io.b
  io.c:=Mux(io.a(31) =/= io.b(31), Mux(io.signed,!io.a(31),io.a(31)),!sub.io.c(31)) | eq
}

class Mult extends Module{
    val io = IO(new Bundle {
    val valid = Input(UInt(32.W))
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(32.W))
    val op = Input(Bool())//select lower 32 bits or higher 32 bits, 0 for low and 1 for high

    val clear_valid = Input(Bool())
    val ready = Output(UInt(32.W))
    val c = Output(UInt(32.W))
    val low_nonzero = Output(Bool())
    val received = Output(UInt(32.W))
    })
    val aL=io.a(15,0)
    val aH=io.a(31,16)
    val bL=io.b(15,0)
    val bH=io.b(31,16)
    val p00 = RegNext(aL * bL)
    val p01 = RegNext(aL * bH)
    val p10 = RegNext(aH * bL)
    val p11 = RegNext(aH * bH)
    val adder1=Module(new Adder)
    val adder2=Module(new Adder)
    val adder3=Module(new Adder)
    val adder4=Module(new Adder)

    val received = RegInit(0.U(32.W))
    val ready1 = RegInit(0.U(32.W))
    val ready2 = RegInit(0.U(32.W))
    adder1.io.a:=p00
    adder1.io.b:=Cat(p01(15,0),0.U(16.W))
    adder3.io.a:=p11
    adder3.io.b:=Cat(0.U(16.W),p01(31,16))

    adder2.io.a:=RegNext(adder1.io.sum)
    adder2.io.b:=Cat(RegNext(p10(15,0)),0.U(16.W))
    adder4.io.a:=RegNext(adder3.io.sum + adder1.io.cout)
    adder4.io.b:=Cat(0.U(16.W),RegNext(p10(31,16)))

    io.c:=Mux(RegNext(RegNext(io.op)),adder4.io.sum + adder2.io.cout,adder2.io.sum)
    io.low_nonzero := adder2.io.sum =/= 0.U
    io.received := received
    io.ready := ready2
    when (io.clear_valid)
    {
      received:=0.U
      ready1:=0.U
      ready2:=0.U
    }.otherwise
    {
      received:=io.valid
      io.received:=received
      ready1:=io.valid
      ready2:=ready1
      io.ready:=ready2
  }
}


class Divider extends Module{//unsigned
  val io = IO(new Bundle {
    val valid = Input(UInt(32.W))
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(32.W))
    val op = Input(Bool())//select quotient or remainder, 1 for quotient and 0 for remainder

    val clear_valid = Input(Bool())

    val ready = Output(UInt(32.W))//which alu
    val c = Output(UInt(32.W))
    val received = Output(UInt(32.W))
    })
    val A = Reg(Vec(32, UInt(32.W)))
    val B = Reg(Vec(32, UInt(32.W)))
    val R = Reg(Vec(32, UInt(32.W)))
    val Q = Reg(Vec(32,UInt(32.W)))
    val rd =RegInit(VecInit(Seq.fill(32)(0.U(32.W))))
    val ops= Reg(Vec(32,Bool()))
    val r= Wire(Vec(32,UInt(33.W)))
    val subs = Seq.fill(32)(Module(new Subtracter))
    val received = RegInit(0.U(32.W))
    received:=io.valid
    for (i<-0 until 32){
       if (i==0){
        r(i):=Cat(0.U(32.W),io.a(31-i))
        A(i):=io.a
        B(i):=io.b
        rd(i):=io.valid
        ops(i):=io.op
       }
       else{
        r(i):=Cat(R(i-1),A(i-1)(31-i))
        A(i):=A(i-1)
        B(i):=B(i-1)
        rd(i):=rd(i-1)
        ops(i):=ops(i-1)
       }
        subs(i).io.a:=r(i)(31,0)
        if (i==0){
          subs(i).io.b:=io.b
        }
        else{
          subs(i).io.b:=B(i-1)
        }
       R(i):=Mux(r(i) < subs(i).io.b, r(i)(31,0),subs(i).io.c)
       if (i==0){
        Q(i):=Cat(0.U(31.W),r(i) >= subs(i).io.b)
       }
       else{
        Q(i):=Cat(Q(i-1)(30,0),r(i) >= subs(i).io.b)
       }
    }
    when (io.clear_valid===true.B){
        received:=0.U
        for (i<-0 until 32){
          rd(i):=0.U
        }
    }
    io.ready:=rd(31)
    io.c:=Mux(ops(31),Q(31),R(31))
    io.received:=received
}



class Executer extends Module{
  val io = IO(new Bundle {
    val valid_num = Input(UInt(32.W))
    val rs = Input(Vec(issueWidth,new RS))
    val cdbs = Input(Vec(ALU_NUM+2,new CDB))

    val clear_valid = Input(Bool())
    val clear_id = Input(SInt(32.W))

    val out_cdb = Output(new CDB)
    val out_space = Output(UInt(32.W))
  })
  val l = RegInit(0.U(4.W))
  val r = RegInit(0.U(4.W))
  val rss = Reg(Vec(RSWidth, new RS()))
  val cur_rs = Reg(new RS)
  val in_process=RegInit(false.B)
  val temp_rs=WireDefault(0.U.asTypeOf(new RS))
  val selected=Wire(Vec(RSWidth+1,Bool()))
  val out_cdb = WireDefault(0.U.asTypeOf(new CDB))
  val can_issue = WireInit(VecInit(Seq.fill(RSWidth)(true.B)))
  val woken = WireDefault(rss)
  val selected_offset = WireDefault(0.U(4.W))
  selected.foreach(_ := false.B)
  rss := woken

  io.out_space:= (RSWidth-1).U-(r-l)
  io.out_cdb:=out_cdb
  val start_process=Wire(Bool())//for telling subclasses to execute rs
  start_process:=false.B
  //find new instruction to execute
  when (io.clear_valid===true.B){//flush
    l:=0.U
    r:=0.U
    in_process:=false.B
  }.otherwise{
    for (i<-0 until ALU_NUM+2){//receive CDB
      for (j<-0 until RSWidth)
      {
        when ((j.U(4.W)-l)<(r-l) && io.cdbs(i).valid)
        {
            when (rss(j).q1 === io.cdbs(i).id.asSInt)
            {
                woken(j).q1:=(-1).S
                woken(j).v1:=io.cdbs(i).value
            }
            when (rss(j).q2 === io.cdbs(i).id.asSInt)
            {
                woken(j).q2:=(-1).S
                woken(j).v2:=io.cdbs(i).value
            }
        }
      }
    }
    for (i<-0 until issueWidth){//receiving RS
      for (j <- 0 until RSWidth) {
        when (i.U<io.valid_num && (r+i.U)(3,0)===j.U){ rss(j):=io.rs(i) }
      }
    }
    r:=r+io.valid_num
    when (in_process===false.B)
    {
      selected(0):=false.B
      for (i<-0 until RSWidth)
      {
        selected(i+1):=selected(i)
        when (i.U<(r-l)&&woken((l+i.U)(3,0)).q1=== (-1).S&&woken((l+i.U)(3,0)).q2=== (-1).S && can_issue(i))
        {
          val len=Wire(UInt(32.W))
          val rs=Wire(new RS)
          val L1=Wire(UInt(32.W))
          val R1=Wire(UInt(32.W))
          val flag=Wire(Bool())
          rs:=woken((l+i.U)(3,0))
          len:=Mux(rs.type_ === OP_TYPE.OP_LW,4.U(32.W),Mux(rs.type_ === OP_TYPE.OP_LH||rs.type_ === OP_TYPE.OP_LHU, 2.U(32.W),Mux(rs.type_ === OP_TYPE.OP_LB||rs.type_ === OP_TYPE.OP_LBU,1.U(32.W),0.U(32.W))))
          L1:=(rs.v1.asSInt+rs.v2(11,0).asSInt).asUInt
          R1:=L1+len
          flag:=false.B
          when (len=/=0.U){
            for (j<-0 until i){
              val tp=Wire(UInt(32.W))
              val temp_rs=Wire(new RS)
              val L2=Wire(UInt(32.W))
              val R2=Wire(UInt(32.W))
              temp_rs:=woken((l+j.U)(3,0))
              tp := Mux(temp_rs.type_ === OP_TYPE.OP_SB,1.U(32.W),Mux(temp_rs.type_ === OP_TYPE.OP_SH, 2.U(32.W),Mux(temp_rs.type_ === OP_TYPE.OP_SW,4.U(32.W),0.U(32.W))))
              L2:=(temp_rs.v1.asSInt+temp_rs.dest(11,0).asSInt).asUInt
              R2:=L2+tp
              when(tp=/=0.U&&(temp_rs.q1=/= (-1).S||(L2<R1&&L1<R2))){
                flag:=true.B
              }
            }
          }
          when (selected(i)===false.B&&flag===false.B)
          {
            selected(i+1):=true.B
            temp_rs:=rs
            cur_rs:=rs
            selected_offset:=i.U
            l:=l+1.U
            start_process:=true.B
            
          }
        } 
      }
    }
  }
  for (j <- 0 until RSWidth) {
    when(start_process && (j.U(4.W)-l-1.U)<selected_offset){
      rss(j):=woken((j+RSWidth-1)%RSWidth)
    }
  }

}

class ALU(val id: Int) extends Executer {
  val aluIO= IO(new Bundle {
    val mult_valid = Input(UInt(32.W))
    val mult_res = Input(UInt(32.W))
    val mult_low_nonzero = Input(Bool())
    val mult_received = Input(UInt(32.W))
    
    val div_valid = Input(UInt(32.W))
    val div_res = Input(UInt(32.W))
    val div_received = Input(UInt(32.W))

    val out_mult_a = Output(UInt(32.W))
    val out_mult_b = Output(UInt(32.W))
    val out_mult_op = Output(Bool())
    val out_mult_valid = Output(Bool())

    val out_div_a = Output(UInt(32.W))
    val out_div_b = Output(UInt(32.W))
    val out_div_op = Output(Bool())
    val out_div_valid = Output(Bool())
  })
  aluIO.out_mult_a:=0.U
  aluIO.out_mult_b:=0.U
  aluIO.out_mult_op:=false.B
  aluIO.out_div_a:=0.U
  aluIO.out_div_b:=0.U
  aluIO.out_div_op:=false.B
  aluIO.out_mult_valid:=false.B
  aluIO.out_div_valid:=false.B
  val temp_out_mult_a=Reg(UInt(32.W))
  val temp_out_mult_b=Reg(UInt(32.W))
  val temp_out_mult_op=Reg(Bool())
  val temp_out_mult_valid=RegInit(false.B)
  val temp_out_div_a=Reg(UInt(32.W))
  val temp_out_div_b=Reg(UInt(32.W))
  val temp_out_div_op=Reg(Bool())
  val temp_out_div_valid=RegInit(false.B)

  val res= WireDefault(0.U(32.W))
  val isMulDiv=Wire(Bool())
  isMulDiv:=false.B
  val waiting=RegInit(false.B)
  val sgn = RegInit(false.B)//for mul and div
  when (io.clear_valid===true.B){
    waiting:=false.B
  }.otherwise{
  when (in_process===true.B)
  {
      when (waiting===true.B)
      {
        when (aluIO.mult_received===(id+1).U||aluIO.div_received===(id+1).U){
            waiting:=false.B
          }.otherwise{//keep sending
            aluIO.out_mult_a:=temp_out_mult_a
            aluIO.out_mult_b:=temp_out_mult_b
            aluIO.out_mult_op:=temp_out_mult_op
            aluIO.out_mult_valid:=temp_out_mult_valid
            aluIO.out_div_a:=temp_out_div_a
            aluIO.out_div_b:=temp_out_div_b
            aluIO.out_div_op:=temp_out_div_op
            aluIO.out_div_valid:=temp_out_div_valid
          }
      }.otherwise{//other operations
        when (aluIO.mult_valid===(id+1).U)
        {
          io.out_cdb.valid:=true.B
          io.out_cdb.id:=cur_rs.id
          io.out_cdb.dest:=cur_rs.dest
          io.out_cdb.value:=Mux(sgn,~aluIO.mult_res + Mux(temp_out_mult_op, !aluIO.mult_low_nonzero, true.B),aluIO.mult_res)
          in_process:=false.B
        }.elsewhen (aluIO.div_valid===(id+1).U)
        {
          io.out_cdb.valid:=true.B
          io.out_cdb.id:=cur_rs.id
          io.out_cdb.dest:=cur_rs.dest
          io.out_cdb.value:=Mux(sgn,~aluIO.div_res+1.U,aluIO.div_res)
          in_process:=false.B
        }
    }
  }
  when (start_process===true.B){
    temp_out_mult_valid:=false.B
    temp_out_div_valid:=false.B
    switch (temp_rs.type_){
      is(OP_TYPE.OP_ADD){
        res:=temp_rs.v1+temp_rs.v2
      }
      is(OP_TYPE.OP_SUB){
        res:=temp_rs.v1-temp_rs.v2
      }
      is (OP_TYPE.OP_AND){
        res:=temp_rs.v1&temp_rs.v2
      }
      is (OP_TYPE.OP_OR){
        res:=temp_rs.v1|temp_rs.v2
      }
      is (OP_TYPE.OP_XOR){
        res:=temp_rs.v1^temp_rs.v2
      }
      is (OP_TYPE.OP_SLL){
        res:=(temp_rs.v1<<(temp_rs.v2(4,0)))(31,0)
      }
      is (OP_TYPE.OP_SRL){
        res:=(temp_rs.v1>>(temp_rs.v2(4,0)))
      }
      is (OP_TYPE.OP_SRA){
        res:=(temp_rs.v1.asSInt>>(temp_rs.v2(4,0))).asUInt
      }
      is (OP_TYPE.OP_SLT){
        res:=Mux(temp_rs.v1.asSInt<temp_rs.v2.asSInt,1.U,0.U)
      }
      is (OP_TYPE.OP_SLTU){
        res:=Mux(temp_rs.v1<temp_rs.v2,1.U,0.U)
      }
      is (OP_TYPE.OP_ADDI){
        res:=(temp_rs.v1.asSInt+temp_rs.v2(11,0).asSInt).asUInt
      }
      is (OP_TYPE.OP_ANDI){
        res:=temp_rs.v1 & Cat(Fill(20, temp_rs.v2(11)), temp_rs.v2(11,0))
      }
      is (OP_TYPE.OP_ORI){
        res:=temp_rs.v1 | Cat(Fill(20, temp_rs.v2(11)), temp_rs.v2(11,0))
      }
      is (OP_TYPE.OP_XORI){
        res:=temp_rs.v1 ^ Cat(Fill(20, temp_rs.v2(11)), temp_rs.v2(11,0))
      }
      is (OP_TYPE.OP_SLLI){
        res:=(temp_rs.v1<<temp_rs.v2(4,0))(31,0)
      }
      is (OP_TYPE.OP_SRLI){
        res:=temp_rs.v1>>temp_rs.v2(4,0)
      }
      is (OP_TYPE.OP_SRAI){
        res:=(temp_rs.v1.asSInt>>temp_rs.v2(4,0)).asUInt
      }
      is (OP_TYPE.OP_SLTI){
        res:=Mux(temp_rs.v1.asSInt<temp_rs.v2(11,0).asSInt,1.U,0.U)
      }
      is (OP_TYPE.OP_SLTIU){
        res:=Mux(temp_rs.v1<Cat(Fill(20, temp_rs.v2(11)), temp_rs.v2(11,0)).asUInt,1.U,0.U)
      }
      is (OP_TYPE.OP_JAL,OP_TYPE.OP_JALR,OP_TYPE.OP_AUIPC,OP_TYPE.OP_LUI){
        res:=temp_rs.v1
      }
      is (OP_TYPE.OP_MUL){
        sgn:=false.B
        waiting:=true.B
        isMulDiv:=true.B
        Seq(aluIO.out_mult_a, temp_out_mult_a).foreach { out => out := temp_rs.v1}
        Seq(aluIO.out_mult_b, temp_out_mult_b).foreach { out => out := temp_rs.v2}
        Seq(aluIO.out_mult_op, temp_out_mult_op).foreach { out => out := false.B}
        Seq(aluIO.out_mult_valid, temp_out_mult_valid).foreach { out => out := true.B}
      }
      is (OP_TYPE.OP_MULHU){
        sgn:=false.B
        waiting:=true.B
        isMulDiv:=true.B
        Seq(aluIO.out_mult_a, temp_out_mult_a).foreach { out => out := temp_rs.v1}
        Seq(aluIO.out_mult_b, temp_out_mult_b).foreach { out => out := temp_rs.v2}
        Seq(aluIO.out_mult_op, temp_out_mult_op).foreach { out => out := true.B}
        Seq(aluIO.out_mult_valid, temp_out_mult_valid).foreach { out => out := true.B}
      }
      is (OP_TYPE.OP_MULH){
        sgn:=temp_rs.v1(31)^temp_rs.v2(31)
        waiting:=true.B
        isMulDiv:=true.B
        Seq(aluIO.out_mult_a, temp_out_mult_a).foreach { out => out := Mux(temp_rs.v1(31),~temp_rs.v1+1.U,temp_rs.v1)}
        Seq(aluIO.out_mult_b, temp_out_mult_b).foreach { out => out := Mux(temp_rs.v2(31),~temp_rs.v2+1.U,temp_rs.v2)}
        Seq(aluIO.out_mult_op, temp_out_mult_op).foreach { out => out := true.B}
        Seq(aluIO.out_mult_valid, temp_out_mult_valid).foreach { out => out := true.B}
      }
      is (OP_TYPE.OP_MULHSU){
        sgn:=temp_rs.v1(31)
        waiting:=true.B
        isMulDiv:=true.B
        Seq(aluIO.out_mult_a, temp_out_mult_a).foreach { out => out := Mux(temp_rs.v1(31),~temp_rs.v1+1.U,temp_rs.v1)}
        Seq(aluIO.out_mult_b, temp_out_mult_b).foreach { out => out := temp_rs.v2}
        Seq(aluIO.out_mult_op, temp_out_mult_op).foreach { out => out := true.B}
        Seq(aluIO.out_mult_valid, temp_out_mult_valid).foreach { out => out := true.B}
      }
      is (OP_TYPE.OP_DIVU){
        sgn:=false.B
        waiting:=true.B
        isMulDiv:=true.B
        Seq(aluIO.out_div_a, temp_out_div_a).foreach { out => out := temp_rs.v1}
        Seq(aluIO.out_div_b, temp_out_div_b).foreach { out => out := temp_rs.v2}
        Seq(aluIO.out_div_op, temp_out_div_op).foreach { out => out := true.B}
        Seq(aluIO.out_div_valid, temp_out_div_valid).foreach { out => out := true.B}
      }
      is (OP_TYPE.OP_DIV){
        when (temp_rs.v2===0.U) { res:="hffffffff".U }.elsewhen (temp_rs.v1==="h80000000".U&&temp_rs.v2==="hffffffff".U){
          res:="h80000000".U
        }.otherwise{
          sgn:=temp_rs.v1(31)^temp_rs.v2(31)
          waiting:=true.B
          isMulDiv:=true.B
          Seq(aluIO.out_div_a, temp_out_div_a).foreach { out => out := Mux(temp_rs.v1(31),~temp_rs.v1+1.U,temp_rs.v1)}
          Seq(aluIO.out_div_b, temp_out_div_b).foreach { out => out := Mux(temp_rs.v2(31),~temp_rs.v2+1.U,temp_rs.v2)}
          Seq(aluIO.out_div_op, temp_out_div_op).foreach { out => out := true.B}
          Seq(aluIO.out_div_valid, temp_out_div_valid).foreach { out => out := true.B}
        }
      }
      is (OP_TYPE.OP_REMU){
        sgn:=false.B
        waiting:=true.B
        isMulDiv:=true.B
        Seq(aluIO.out_div_a, temp_out_div_a).foreach { out => out := temp_rs.v1}
        Seq(aluIO.out_div_b, temp_out_div_b).foreach { out => out := temp_rs.v2}
        Seq(aluIO.out_div_op, temp_out_div_op).foreach { out => out := false.B}
        Seq(aluIO.out_div_valid, temp_out_div_valid).foreach { out => out := true.B}
      }
      is (OP_TYPE.OP_REM){
        when (temp_rs.v2===0.U) { res:=temp_rs.v1 }.elsewhen (temp_rs.v1==="h80000000".U&&temp_rs.v2==="hffffffff".U){//special case
          res:=0.U
        }.otherwise{
          sgn:=temp_rs.v1(31)
          waiting:=true.B
          isMulDiv:=true.B
          Seq(aluIO.out_div_a, temp_out_div_a).foreach { out => out := Mux(temp_rs.v1(31),~temp_rs.v1+1.U,temp_rs.v1)}
          Seq(aluIO.out_div_b, temp_out_div_b).foreach { out => out := Mux(temp_rs.v2(31),~temp_rs.v2+1.U,temp_rs.v2)}
          Seq(aluIO.out_div_op, temp_out_div_op).foreach { out => out := false.B}
          Seq(aluIO.out_div_valid, temp_out_div_valid).foreach { out => out := true.B}
        }
      }
    }
    when (isMulDiv===true.B){
      in_process:=true.B
    }.otherwise{
        io.out_cdb.valid:=true.B
          io.out_cdb.id:=temp_rs.id
        io.out_cdb.dest:=temp_rs.dest
        io.out_cdb.value:=res
    }
  }
  }
}

class LSU extends Executer{
  val lsuIO= IO(new Bundle {
    val robs = Input(Vec(ROBWidth,new ROB))
    val l = Input(UInt(4.W))
    val r = Input(UInt(4.W))

    val addr_valid = Input(Bool())
    val addr_received = Input(Bool())
    val addr_value = Input(UInt(32.W))
    val out_valid = Output(Bool())
    val out_addr = Output(UInt(32.W))
  })
  val res=WireDefault(0.U(32.W))
  val wanted=Reg(UInt(32.W))
  val waiting=RegInit(false.B)//0 already received, 1 wait for memory
  val canOutput= Wire(Bool())
  canOutput:=true.B
  lsuIO.out_valid:=false.B
  lsuIO.out_addr:=wanted
  val forwarded=Reg(Vec(4,UInt(9.W)))
  val merged=Wire(Vec(4,UInt(8.W)))
  for(j<-0 until 4){ merged(j):=Mux(forwarded(j)(8),lsuIO.addr_value(8*j+7,8*j),forwarded(j)(7,0)) }
  val load_value=merged.asUInt
  // A load may not bypass an older store whose address/data is still unknown.
  for(i<-0 until RSWidth){
    val candidate=woken((l+i.U)(3,0))
    val load=candidate.type_ ===OP_TYPE.OP_LB || candidate.type_ ===OP_TYPE.OP_LBU || candidate.type_ ===OP_TYPE.OP_LH || candidate.type_ ===OP_TYPE.OP_LHU || candidate.type_ ===OP_TYPE.OP_LW
    for(j<-0 until ROBWidth){
      when(load && (j.U(4.W)-lsuIO.l)<(lsuIO.r-lsuIO.l) && lsuIO.robs(j).id<candidate.id && lsuIO.robs(j).type_ >0.S && !lsuIO.robs(j).ready){ can_issue(i):=false.B }
    }
  }

  when (io.clear_valid===true.B){
    waiting:=false.B
  }.otherwise{
  when (in_process===true.B){
      when (waiting===true.B)
      {
        lsuIO.out_valid:=true.B
        lsuIO.out_addr:=wanted
        when (lsuIO.addr_received===true.B)
        {
          waiting:=false.B
        }.otherwise
        {
          lsuIO.out_valid:=true.B
          lsuIO.out_addr:=wanted
          canOutput:=false.B
        }
      }
      when (canOutput===true.B&&lsuIO.addr_valid===true.B){
        io.out_cdb.valid:=true.B
        io.out_cdb.id:=cur_rs.id
        switch (cur_rs.type_)
        {
          is (OP_TYPE.OP_LB){
            io.out_cdb.value:=Cat(Fill(24, load_value(7)), load_value(7,0))
          }
          is (OP_TYPE.OP_LBU){
            io.out_cdb.value:=load_value(7,0)
          }
          is (OP_TYPE.OP_LH){
            io.out_cdb.value:=Cat(Fill(16, load_value(15)), load_value(15,0))
          }
          is (OP_TYPE.OP_LHU){
            io.out_cdb.value:=load_value(15,0)
          }
          is (OP_TYPE.OP_LW){
            io.out_cdb.value:=load_value
          }
        }
        io.out_cdb.dest:=cur_rs.dest
        in_process:=false.B
      }
  }
    
  val len=WireDefault(0.U(32.W))
  val L=WireDefault(0.U(32.W))
  val R=WireDefault(0.U(32.W))
  val vals=Wire(Vec(4,UInt(9.W)))
  for (i<-0 until 4){vals(i):=0x100.U}
  when (start_process===true.B){
    len:=Mux(temp_rs.type_ ===OP_TYPE.OP_LW,4.U,Mux(temp_rs.type_ ===OP_TYPE.OP_LH || temp_rs.type_ ===OP_TYPE.OP_LHU,2.U,Mux(temp_rs.type_ ===OP_TYPE.OP_LB || temp_rs.type_ ===OP_TYPE.OP_LBU,1.U,0.U)))
    when (len=/=0.U){//load
      L:=(temp_rs.v1.asSInt+temp_rs.v2(11,0).asSInt).asUInt
      R:=L+len
      for (i<-0 until ROBWidth){
        when (i.U<(lsuIO.r-lsuIO.l)){
          val rb=Wire(new ROB)
          rb:=lsuIO.robs((lsuIO.l+i.U)(3,0))
          when (rb.ready && rb.id < temp_rs.id && rb.type_ > 0.S && rb.dest.asUInt < R && rb.dest.asUInt+rb.type_.asUInt > L){
            for (j<-0 until 4){
              val offset = Wire(UInt(32.W))
              offset:=rb.dest.asUInt+j.U-L
              when (j.S < rb.type_ && offset.asSInt >= 0.S && offset< len){
                vals(offset(1,0)):=rb.value(8*j+7,8*j)
              }
            }
          }
        }
      }
      forwarded:=vals
      val flag=Wire(Bool())
      flag:=false.B
      for (i<-0 until 4)
      {
          when (i.U < len && vals(i)===0x100.U)
          {
            lsuIO.out_addr:=L
            lsuIO.out_valid:=true.B
            wanted:=L
            waiting:= !lsuIO.addr_received
            in_process:=true.B
            flag:=true.B
          }
      }
      when (flag===false.B){
        switch (temp_rs.type_)
        {
          is (OP_TYPE.OP_LB){
            res:=Cat(Fill(24, vals(0)(7)), vals(0)(7,0))
          }
          is (OP_TYPE.OP_LBU){
            res:=vals(0)(7,0)
          }
          is (OP_TYPE.OP_LH){
            res:=Cat(Fill(16, vals(1)(7)), vals(1)(7,0),vals(0)(7,0))
          }
          is (OP_TYPE.OP_LHU){
            res:=Cat(vals(1)(7,0),vals(0)(7,0))
          }
          is (OP_TYPE.OP_LW){
            res:=Cat(vals(3)(7,0),vals(2)(7,0),vals(1)(7,0),vals(0)(7,0))
          }
        }
        io.out_cdb.valid:=true.B
        io.out_cdb.id:=temp_rs.id
        io.out_cdb.value:=res
        io.out_cdb.dest:=temp_rs.dest
      }
    }.otherwise{//store
      io.out_cdb.valid:=true.B
        io.out_cdb.id:=temp_rs.id
      io.out_cdb.value:=temp_rs.v2
      io.out_cdb.dest:=temp_rs.v1.asSInt+temp_rs.dest(11,0).asSInt
    }
  }
  }
}


class BRU extends Executer{
  val res=WireDefault(false.B)
  when (io.clear_valid===false.B)
  {
    when (start_process===true.B){
      switch (temp_rs.type_){
        is (OP_TYPE.OP_BEQ){
          res:=(temp_rs.v1===temp_rs.v2)^temp_rs.dest(0)
        }
        is (OP_TYPE.OP_BGE){
          res:=(temp_rs.v1.asSInt>=temp_rs.v2.asSInt)^temp_rs.dest(0)
        }
        is (OP_TYPE.OP_BGEU){
          res:=(temp_rs.v1>=temp_rs.v2)^temp_rs.dest(0)
        }
        is (OP_TYPE.OP_BLT){
          res:=(temp_rs.v1.asSInt<temp_rs.v2.asSInt)^temp_rs.dest(0)
        }
        is (OP_TYPE.OP_BLTU){
          res:=(temp_rs.v1<temp_rs.v2)^temp_rs.dest(0)
        }
        is (OP_TYPE.OP_BNE){
          res:=(temp_rs.v1=/=temp_rs.v2)^temp_rs.dest(0)
        }
      }
      io.out_cdb.valid:=true.B
      io.out_cdb.id:=temp_rs.id
      io.out_cdb.dest:=(-2).S
      io.out_cdb.value:=res
    }
  }
}