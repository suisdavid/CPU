//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused
package Executer

import Op._
import RS._
import CDB._
import Config._
import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import _root_.circt.stage.ChiselStage
import org.apache.commons.io.filefilter.CanExecuteFileFilter
class Adder extends Module {
  val io = IO(new Bundle {
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(32.W))
    val sum = Output(UInt(32.W))
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
  io.sum := pBits(31, 0) ^ cBits  
}

class LShifter extends Module{
    val io = IO(new Bundle {
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(5.W))
    val c = Output(UInt(32.W))
  })
  val a1,a2,a3,a4=Wire(UInt(32.W))
  a1:=MUX(io.b(0),Cat(io.a(30,0),0.U(1.W)),io.a)
  a2:=MUX(io.b(1),Cat(a1(29,0),0.U(2.W)),a1)
  a3:=MUX(io.b(2),Cat(a2(27,0),0.U(4.W)),a2)
  a4:=MUX(io.b(3),Cat(a3(23,0),0.U(8.W)),a3)
  io.c:=MUX(io.b(4),Cat(a4(15,0),0.U(16.W)),a4)
}

class RShifter extends Module{
    val io = IO(new Bundle {
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(5.W))
    val signed = Input(Bool())
    val c = Output(UInt(32.W))
  })
  val a1,a2,a3,a4=Wire(UInt(32.W))
  when (signed&io.a(31))
  {
        a1:=MUX(io.b(0),Cat(1.U(1.W),io.a(31,1)),io.a)
        a2:=MUX(io.b(1),Cat(3.U(2.W),a1(31,2)),a1)
        a3:=MUX(io.b(2),Cat(15.U(4.W),a2(31,4)),a2)
        a4:=MUX(io.b(3),Cat(255.U(8.W),a3(31,8)),a3)
        io.c:=MUX(io.b(4),Cat(65535.U(15.W),a4(31,16)),a4)
  }.otherwise{
        a1:=MUX(io.b(0),Cat(0.U(1.W),io.a(31,1)),io.a)
        a2:=MUX(io.b(1),Cat(0.U(2.W),a1(31,2)),a1)
        a3:=MUX(io.b(2),Cat(0.U(4.W),a2(31,4)),a2)
        a4:=MUX(io.b(3),Cat(0.U(8.W),a3(31,8)),a3)
        io.c:=MUX(io.b(4),Cat(0.U(15.W),a4(31,16)),a4)
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
  val reverted = signed&io.a(31)
  val sub=Module(new Subtracter)
  sub.io.a:=io.a
  sub.io.b:=io.b
  io.c:=((!sub.io.c(31))^reverted)|eq
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
    val received = Output(UInt(32.W))
    })
    val aL=io.a(15,0)
    val aH=io.a(31,16)
    val bL=io.b(15,0)
    val bH=io.b(31,16)
    val p00 = RegNext(aLo * bLo)
    val p01 = RegNext(aLo * bHi)
    val p10 = RegNext(aHi * bLo)
    val p11 = RegNext(aHi * bHi)
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
    adder4.io.a:=RegNext(adder3.io.sum)
    adder4.io.b:=Cat(0.U(16.W),RegNext(p10(31,16)))

    io.c:=Mux(RegNext(RegNext(io.op)),adder2.io.sum,adder4.io.sum)
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
    val r= Wire(Vec(32,Uint(32.W)))
    val subs = Seq.fill(32)(Module(new Subtracter))
    val received = RegInit(0.U(32.W))
    received:=io.valid
    for (i<-0 until 32){
       if (i==0){
        r(i):=Cat(0.U(31.W),io.a(31-i))
        A(i):=io.a
        B(i):=io.b
        rd(i):=io.valid
        ops(i):=io.op
       }
       else{
        r(i):=Cat(R(i-1)(30,0),A(i-1)(31-i))
        A(i):=A(i-1)
        B(i):=B(i-1)
        rd(i):=rd(i-1)
        ops(i):=ops(i-1)
       }
        subs(i).io.a:=r(i)
        if (i==0){
          subs(i).io.b:=io.b
        }
        else{
          subs(i).io.b:=B(i-1)
        }
       R(i):=Mux(subs(i).io.c(31),r(i),subs(i).io.c)
       if (i==0){
        Q(i):=Cat(0.U(31.W),Mux(subs(i).io.c(31),false.B,true.B))
       }
       else{
        Q(i):=Cat(Q(i-1)(30,0),Mux(subs(i).io.c(31),false.B,true.B))
       }
    }
    when (clear_valid===true.B){
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
  val rss = Reg(Vec(8, new RS()))
  val cur_rs = Reg(new RS)
  val in_process=RegInit(false.B)
  val temp_rs=Wire(new RS)
  val selected=Wire(Vec(RSWidth+1,Bool()))
  val init = Wire(new CDB)
  init.id :=0.U
  val out_cdb= RegInit(init)

  io.out_space:= (RSWidth-1).U-(r-l)
  io.out_cdb:=out_cdb
  val start_process=Wire(Bool())//for telling subclasses to execute rs
  start_process:=false.B
  //find new instruction to execute
  when (io.clear_valid===true.B){//flush
    val newr=Wire(UInt(32.W))
    newr:=l.U
    for (i<-0 until RSWidth){
      when (i.U<(r-l)&&rss(l+i.U).id<=io.clear_id){
          newr:=l+(i+1).U
      }
    }
    r:=newr
    in_process:=false.B
  }.otherwise{
    for (i<-0 until ALU_NUM+2){//receive CDB
      for (j<-0 until RSWidth)
      {
        when (j.U<(r-l))
        {
            when (rss(l+j.U).q1==io.cdbs(i).id)
            {
                rss(l+j.U).q1:=(-1).S
                rss(l+j.U).v1:=io.cdbs(i).value
            }
            when (rss(l+j.U).q2==io.cdbs(i).id)
            {
                rss(l+j.U).q2:=(-1).S
                rss(l+j.U).v2:=io.cdbs(i).value
            }
        }
      }
    }
    for (i<-0 until issueWidth){//receiving RS
      when (i.U<io.valid_num){
        rss(r+i.U):=rs(i)
      }
    }
    r:=r+io.valid_num
    when (in_process===false.B)
    {
      selected(0):=false.B
      for (i<-0 until RSWidth)
      {
        selected(i+1):=selected(i)
        when (i.U<(r-l)&&rss(l+i.U).q1===-1.S&&rss(l+i.U).q2===-1.S)
        {
          val len=Wire(Uint(32.W))
          val rs=Wire(new RS)
          val L1=Wire(UInt(32.W))
          val R1=Wire(UInt(32.W))
          val flag=Wire(Bool())
          rs:=rss(l+i.U)
          len:=MUX(rs.type_ === OP_TYPE.OP_LW,4.U(32.W),MUX(rs.type_ === OP_TYPE.LH||rs.type_ === OP_TYPE.LHU, 2.U(32.W),MUX(rs.type_ === OP_TYPE.LB||rs.type_ === OP_TYPE.LBU,1.U(32.W),0.U(32.W))))
          L1:=(rs.v1.asSInt+rs.v2(11,0).asSInt).asUInt
          R1:=L1+len
          flag:=false.B
          when (len=/=0.U){
            for (j<-0 until i){
              val tp=Wire(UInt(32.W))
              val temp_rs=Wire(new RS)
              val L2=Wire(UInt(32.W))
              val R2=Wire(UInt(32.W))
              temp_rs:=rss(l+j.U)
              tp := MUX(temp_rs.type_ === OP_TYPE.OP_SB,1.U(32.W),MUX(temp_rs.type_ === OP_TYPE.OP_SH, 2.U(32.W),MUX(temp_rs.type_ === OP_TYPE.OP_SW,4.U(32.W),0.U(32.W))))
              L2:=(temp_rs.v1.asSInt+temp_rs.dest(11,0).asSInt).asUInt
              R2:=L2+tp
              when(tp=/=0.U&&(temp_rs.q1!=-1.S||(L2<R1&&L1<R2))){
                flag:=true.B
              }
            }
          }
          when (selected(i)===false.B&&flag===false.B)
          {
            selected(i+1):=true.B
            temp_rs:=rs
            cur_rs:=rs
            for (j<-0 until i){
              rss(l+(j+1).U):=rss(i+j.U)
            }
            l:=l+1.U
            start_process:=true.B
            
          }
        } 
      }
    }
  }
}

class ALU(val id: Int) extends Executer {
  val aluIO= IO(new Bundle {
    val mult_valid = Input(UInt(32.W))
    val mult_res = Input(UInt(32.W))
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

  val res= Wire(UInt(32.W))
  val isMulDiv=Wire(Bool())
  isMulDiv:=false.B
  val waiting=RegInit(false.B)
  val sgn = RegInit(false.B)//for mul and div
  when (io.clear_valid===true.B){
    waiting:=false.B
  }.otherwise{
  when (in_process==true.B)
  {
      when (waiting===true.B)
      {
        when (aluIO.mult_received===(i+1).U||aluIO.div_received===(i+1).U){
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
        when (io.mult_valid==(id+1).U)
        {
          io.out_cdb.id:=cur_rs.id
          io.out_cdb.dest:=cur_rs.dest
          io.out_cdb.value:=MUX(sgn,~io.mult_res+1.U,io.mult_res)
          in_process=false.B
        }.elsewhen (io.div_valid==(id+1).U)
        {
          io.out_cdb.id:=cur_rs.id
          io.out_cdb.dest:=cur_rs.dest
          io.out_cdb.value:=MUX(sgn,~io.div_res+1.U,io.div_res)
          in_process=false.B
        }
    }
  }
  when (start_process===true.B){
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
        res:=(temp_rs.v1.asSInt>>(temp_rs.v2(4,0))).asUInt
      }
      is (OP_TYPE.OP_SRA){
        res:=(temp_rs.v1.asSInt>>(temp_rs.v2(4,0))).asUInt
      }
      is (OP_TYPE.OP_SLT){
        res:=MUX(temp_rs.v1.asSInt<temp_rs.v2.asSInt,1.U,0.U)
      }
      is (OP_TYPE.OP_SLTU){
        res:=MUX(temp_rs.v1<temp_rs.v2,1.U,0.U)
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
        res:=(temp_rs.v1<<temp_rs.v2)(31,0)
      }
      is (OP_TYPE.OP_SRLI){
        res:=temp_rs.v1>>temp_rs.v2
      }
      is (OP_TYPE.OP_SRAI){
        res:=(temp_rs.v1.asSInt>>temp_rs.v2).asUInt
      }
      is (OP_TYPE.OP_SLTI){
        res:=MUX(temp_rs.v1.asSInt<temp_rs.v2(11,0).asSInt,1.U,0.U)
      }
      is (OP_TYPE.OP_SLTUI){
        res:=MUX(temp_rs.v1<Cat(Fill(20, temp_rs.v2(11)), temp_rs.v2(11,0)).asUInt,1.U,0.U)
      }
      is (OP_TYPE.OP_JAL,OP_TYPE.OP_JALR,OP_TYPE.OP_AUIPC,OP_TYPE.OP_LUI){
        res:=temp_rs.v1
      }
      is (OP_TYPE.MUL){
        sgn:=false.B
        waiting:=true.B
        isMulDiv:=true.B
        Seq(aluIO.out_mult_a, temp_out_mult_a).foreach { out => out := temp_rs.v1}
        Seq(aluIO.out_mult_b, temp_out_mult_b).foreach { out => out := temp_rs.v2}
        Seq(aluIO.out_mult_op, temp_out_mult_op).foreach { out => out := false.B}
        Seq(aluIO.out_mult_valid, temp_out_mult_valid).foreach { out => out := true.B}
      }
      is (OP_TYPE.MULHU){
        sgn:=false.B
        waiting:=true.B
        isMulDiv:=true.B
        Seq(aluIO.out_mult_a, temp_out_mult_a).foreach { out => out := temp_rs.v1}
        Seq(aluIO.out_mult_b, temp_out_mult_b).foreach { out => out := temp_rs.v2}
        Seq(aluIO.out_mult_op, temp_out_mult_op).foreach { out => out := true.B}
        Seq(aluIO.out_mult_valid, temp_out_mult_valid).foreach { out => out := true.B}
      }
      is (OP_TYPE.MULH){
        sgn:=temp_rs.v1(31)^temp_rs.v2(31)
        waiting:=true.B
        isMulDiv:=true.B
        Seq(aluIO.out_mult_a, temp_out_mult_a).foreach { out => out := MUX(temp_rs.v1(31),~temp_rs.v1+1.U,temp_rs.v1)}
        Seq(aluIO.out_mult_b, temp_out_mult_b).foreach { out => out := MUX(temp_rs.v2(31),~temp_rs.v2+1.U,temp_rs.v2)}
        Seq(aluIO.out_mult_op, temp_out_mult_op).foreach { out => out := true.B}
        Seq(aluIO.out_mult_valid, temp_out_mult_valid).foreach { out => out := true.B}
      }
      is (OP_TYPE.MULHSU){
        sgn:=temp_rs.v1(31)
        waiting:=true.B
        isMulDiv:=true.B
        Seq(aluIO.out_mult_a, temp_out_mult_a).foreach { out => out := MUX(temp_rs.v1(31),~temp_rs.v1+1.U,temp_rs.v1)}
        Seq(aluIO.out_mult_b, temp_out_mult_b).foreach { out => out := temp_rs.v2}
        Seq(aluIO.out_mult_op, temp_out_mult_op).foreach { out => out := true.B}
        Seq(aluIO.out_mult_valid, temp_out_mult_valid).foreach { out => out := true.B}
      }
      is (OP_TYPE.DIVU){
        sgn:=false.B
        waiting:=true.B
        isMulDiv:=true.B
        Seq(aluIO.out_div_a, temp_out_div_a).foreach { out => out := temp_rs.v1}
        Seq(aluIO.out_div_b, temp_out_div_b).foreach { out => out := temp_rs.v2}
        Seq(aluIO.out_div_op, temp_out_div_op).foreach { out => out := true.B}
        Seq(aluIO.out_div_valid, temp_out_div_valid).foreach { out => out := true.B}
      }
      is (OP_TYPE.DIV){
        when (temp_rs.v1===0x80000000.U&&temp_rs.v2===0xFFFFFFFF.U){
          res:=0x80000000.U
        }.otherwise{
          sgn:=temp_rs.v1(31)^temp_rs.v2(31)
          waiting:=true.B
          isMulDiv:=true.B
          Seq(aluIO.out_div_a, temp_out_div_a).foreach { out => out := MUX(temp_rs.v1(31),~temp_rs.v1+1.U,temp_rs.v1)}
          Seq(aluIO.out_div_b, temp_out_div_b).foreach { out => out := MUX(temp_rs.v2(31),~temp_rs.v2+1.U,temp_rs.v2)}
          Seq(aluIO.out_div_op, temp_out_div_op).foreach { out => out := true.B}
          Seq(aluIO.out_div_valid, temp_out_div_valid).foreach { out => out := true.B}
        }
      }
      is (OP_TYPE.REMU){
        sgn:=false.B
        waiting:=true.B
        isMulDiv:=true.B
        Seq(aluIO.out_div_a, temp_out_div_a).foreach { out => out := temp_rs.v1}
        Seq(aluIO.out_div_b, temp_out_div_b).foreach { out => out := temp_rs.v2}
        Seq(aluIO.out_div_op, temp_out_div_op).foreach { out => out := false.B}
        Seq(aluIO.out_div_valid, temp_out_div_valid).foreach { out => out := true.B}
      }
      is (OP_TYPE.REM){
        when (temp_rs.v1===0x80000000.U&&temp_rs.v2===0xFFFFFFFF.U){//special case
          res:=0.U
        }.otherwise{
          sgn:=temp_rs.v1(31)
          waiting:=true.B
          isMulDiv:=true.B
          Seq(aluIO.out_div_a, temp_out_div_a).foreach { out => out := MUX(temp_rs.v1(31),~temp_rs.v1+1.U,temp_rs.v1)}
          Seq(aluIO.out_div_b, temp_out_div_b).foreach { out => out := MUX(temp_rs.v2(31),~temp_rs.v2+1.U,temp_rs.v2)}
          Seq(aluIO.out_div_op, temp_out_div_op).foreach { out => out := false.B}
          Seq(aluIO.out_div_valid, temp_out_div_valid).foreach { out => out := true.B}
        }
      }
    }
    when (isMulDiv===true.B){
      in_process:=true.B
    }.otherwise{
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
  val res=Wire(UInt(32.W))
  val wanted=Reg(UInt(32.W))
  val waiting=RegInit(false.B)//0 already received, 1 wait for memory
  val canOutput= Wire(Bool())
  canOutput:=true.B
  lsuIO.out_valid:=false.B

  when (io.clear_valid===true.B){
    waiting:=false.B
  }.otherwise{
  when (in_process===true.B){
      when (waiting===true.B)
      {
        when (lsuIO.addr_received===true.B)
        {
          waiting:=false.B
        }.otherwise
        {
          io.out_valid:=true.B
          io.out_addr:=wanted
          canOutput:=false.B
        }
      }
      when (canOutput===true.B&&lsuIO.addr_valid===true.B){
        io.out_cdb.id:=cur_rs.id
        switch (cur_rs.type_)
        {
          is (OP_TYPE.OP_LB){
            io.out_cdb.value:=Cat(Fill(24, lsuIO.addr_value(7)), lsuIO.addr_value(7,0))
          }
          is (OP_TYPE.OP_LBU){
            rio.out_cdb.value:=lsuIO.addr_value(7,0)
          }
          is (OP_TYPE.OP_LH){
            io.out_cdb.value:=Cat(Fill(16, lsuIO.addr_value(15)), lsuIO.addr_value(15,0))
          }
          is (OP_TYPE.OP_LHU){
            io.out_cdb.value:=lsuIO.addr_value(15,0)
          }
          is (OP_TYPE.OP_LW){
            io.out_cdb.value:=lsuIO.addr_value
          }
        }
        io.out_cdb.dest:=cur_rs.dest
        in_process:=false.B
      }
  }
    
  val len=Wire(UInt(32.W))
  val L=Wire(UInt(32.W))
  val R=Wire(UInt(32.W))
  val vals=Wire(Vec(4,UInt(9.W)))
  for (i<-0 until 4){vals(i):=0x100.U}
  when (start_process===true.B){
    len:=MUX(rs.type_ === OP_TYPE.OP_LW,4.U),MUX(rs.type_ === OP_TYPE.LH||rs.type_ === OP_TYPE.LHU, 2.U,MUX(rs.type_ === OP_TYPE.LB||rs.type_ === OP_TYPE.LBU,1.U,0.U))
    when (len=/=0.U){//load
      L:=(temp_rs.v1.asSint+temp_rs.v2(11,0).asSInt).asUInt
      R:=L+len
      for (i<-0 until ROBwidth){
        when (i.U<(lsuIO.r-lsuIO.l)){
          val rb=Wire(new ROB)
          rb:=lsuIo.robs(lsuIo.l+i.U)
          when (rb.id < temp_rs.id && rb.type_ > 0.S && rb.dest.asUInt < R && rb.dest.asUInt+rb.type_.asUInt > L){
            for (j<-0 until 4){
              val offset = Wire(UInt(32.W))
              offset:=rb.dest.asUInt+j.U-L
              when (j.S < rb.type_ && offset.asSInt >= 0.S && offset< len){
                vals(offset):=rb(i).value(8*j+7,8*j)
              }
            }
          }
        }
      }
      val flag=Wire(Bool())
      flag:=false.B
      for (i<-0 until 4)
      {
          when (i.U < len && vals(i)===0x100.U)
          {
            io.out_addr:=L
            io.out_valid:=true.B
            wanted:=L
            waiting:=true.B
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
        io.out_cdb.id:=temp_rs.id
        io.out_cdb.value:=res
        io.out_cdb.dest:=temp_rs.dest
      }
    }.otherwise{//store
      io.out_cdb.id:=temp_rs.id
      io.out_cdb.value:=temp_rs.v2
      io.out_cdb.dest:=temp_rs.v1.asSInt+temp_rs.dest(11,0).asSInt
    }
  }
  }
}


class BRU extends Executer{
  val res=Wire(Bool())
  when (io.clear_valid===false.B)
  {
    when (start_process===true.B){
      switch (temp_rs.type_){
        is (OP_TYPE.OP_BEQ){
          res:=(temp_rs.v1===temp_rs.v2)^temp_rs.dest
        }
        is (OP_TYPE.OP_BGE){
          res:=(temp_rs.v1.asSInt>=temp_rs.v2.asSInt)^temp_rs.dest
        }
        is (OP_TYPE.OP_BGEU){
          res:=(temp_rs.v1>=temp_rs.v2)^temp_rs.dest
        }
        is (OP_TYPE.OP_BLT){
          res:=(temp_rs.v1.asSInt<temp_rs.v2.asSInt)^temp_rs.dest
        }
        is (OP_TYPE.OP_BLTU){
          res:=(temp_rs.v1<temp_rs.v2)^temp_rs.dest
        }
        is (OP_TYPE.OP_BNE){
          res:=(temp_rs.v1=/=temp_rs.v2)^temp_rs.dest
        }
      }
      io.out_cdb.id:=temp_rs.id
      io.out_cdb.dest:=(-2).S
      io.out_cdb.value:=res
    }
  }
}