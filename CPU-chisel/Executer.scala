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
    val valid = Input(Bool())
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(32.W))
    val op = Input(Bool())//select lower 32 bits or higher 32 bits
    val ready = Output(Bool())
    val c = Output(UInt(32.W))
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

    adder1.io.a:=p00
    adder1.io.b:=Cat(p01(15,0),0.U(16.W))
    adder3.io.a:=p11
    adder3.io.b:=Cat(0.U(16.W),p01(31,16))

    adder2.io.a:=RegNext(adder1.io.sum)
    adder2.io.b:=Cat(RegNext(p10(15,0)),0.U(16.W))
    adder4.io.a:=RegNext(adder3.io.sum)
    adder4.io.b:=Cat(0.U(16.W),RegNext(p10(31,16)))

    io.c:=Mux(RegNext(RegNext(io.op)),adder2.io.sum,adder4.io.sum)
    io.ready:=RegNext(RegNext(io.valid, init = false.B),init = false.B)
}


class Divider extends Module{//unsigned
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val a  = Input(UInt(32.W))
    val b  = Input(UInt(32.W))
    val op = Input(Bool())//select quotient or remainder
    val ready = Output(Bool())
    val c = Output(UInt(32.W))
    })
    val A = Reg(Vec(32, UInt(32.W)))
    val B = Reg(Vec(32, UInt(32.W)))
    val R = Reg(Vec(32, UInt(32.W)))
    val Q = Reg(Vec(32,UInt(32.W)))
    val rd =RegInit(VecInit(Seq.fill(32)(0.U(32.W))))
    val ops= Reg(Vec(32,Bool()))
    val r= Wire(Vec(32,Uint(32.W)))
    val subs = Seq.fill(32)(Module(new Subtracter))
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
    io.ready:=rd(31)
    io.c:=Mux(ops(31),Q(31),R(31))
}



class Executer extends Module{//unsigned
  val io = IO(new Bundle {
    val valid = Input(Bool())
    val receive = Input(Bool())
    val cdb = Input(new CDB())

    val clear = Input(Bool())
    val clear_id = Input(SInt(32.W))

    val rs  = Input(new RS())
    val ready = Output(Bool())
  })
  val l = RegInit(0.U(3.W))
  val r = RegInit(0.U(3.W))
  val time = RegInit(0.U(8.W))
  val buffer = Reg(new RS())
  val has_buffer = RegInit(false.B)
  val rss = Reg(Vec(8, new RS()))
  val cur_rs = Reg(new RS())
  when (has_buffer){
    rss(r.asUInt):=buffer
    has_buffer:=false.B
    r:=r+1.U
  }
  val rd = io.valid&((r+1.U)=/=l)
  has_buffer:=rd
  io.ready:=rd
  buffer:=io.rs
  when (time===0.U){

  }
}
class Executer{
    protected:
        RS rss[maxm],cur_rs,buffer;
        int l,r,time;
        bool has_buffer;
        Executer(){
            l=0;r=0;time=0;has_buffer=0;
        }
        int run(){
            if (!time){
                for (int id=l;id!=r;id=(id+1)%maxm){//find the first ready instruction
                    if (rss[id].q1==-1&&rss[id].q2==-1){
                        int len=is_load(rss[id].type);
                        if (len){//load operation
                            int L1=rss[id].v1+signed12(rss[id].v2),R1=L1+len,flag=0;
                            for (int j=l;j!=id;j=(j+1)%maxm){
                                int tp=is_store(rss[j].type);
                                if (!tp){continue;}
                                int L2=rss[j].v1+signed12(rss[j].dest),R2=L2+tp;
                                if (L2<R1&&L1<R2){
                                    flag=1;break;
                                }
                            }
                            if (flag)//overlapped store
                            {
                                continue;
                            }
                        }
                        cur_rs=rss[id];
                        time=1;
                        for (int i=id;i!=l;i=(i+maxm-1)%maxm){
                            rss[i]=rss[(i+maxm-1)%maxm];
                        }
                        l=(l+1)%maxm;
                        return 1;
                    }
                    if (is_store(rss[id].type)&&rss[id].q1!=-1){//store op with unknown address
                        return 0;
                    }
                }
            }
            else if (--time==0){
                return 2;
            }
            return 0;
        }
    public:
        bool full(){
            return (r+1)%maxm==l;
        }
        void add(RS rs){
            buffer=rs;has_buffer=1;
        }
        void receive(CDB cdb){
            for (int i=l;i!=r;i=(i+1)%maxm){
                if (rss[i].q1==cdb.id){
                    rss[i].q1=-1;rss[i].v1=cdb.val;
                }
                if (rss[i].q2==cdb.id){
                    rss[i].q2=-1;rss[i].v2=cdb.val;
                }
            }
        }
        void clear(int id){
            while (r!=l&&rss[(r+maxm-1)%maxm].id>id){
                r=(r+maxm-1)%maxm;
            }
            if (r==l){
                time=0;//completely cleared
            }
        }
        void update(){
            if (has_buffer)
            {
                rss[r]=buffer;
                r=(r+1)%maxm;
                has_buffer=0;
            }
        }
};
