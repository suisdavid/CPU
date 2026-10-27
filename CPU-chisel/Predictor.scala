//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused
package Predictor


import Op._
import RS._
import CDB._
import Config._
import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import _root_.circt.stage.ChiselStage
import org.apache.commons.io.filefilter.CanExecuteFileFilter
import Config.CPUConfig.issueWidth

class Predictor extends Module {//0 cycle
  val io = IO(new Bundle {
    val qaddrs = Input(Vec(issueWidth,UInt(32.W)))
    val uvalid = Input(Bool())
    val uaddr = Input(UInt(32.W))
    val ures = Input(UInt(32.W))
    val out = Output(Vec(issueWidth,UInt(32.W)))
  })
  val PHT = RegInit(VecInit(Seq.fill(KSIZE)(1.U(3.W))))//2 bit saturate predictor,used for local prediction
  val LHT = RegInit(VecInit(Seq.fill(PSIZE)(0.U(KBIT.W))))//k-bit LOCAL HISTORY
  val Gshare_status = RegInit(VecInit(Seq.fill(PSIZE)(1.U(3.W))))//used for global prediction,00:strongly not taken, 01: weakly not taken, 02: weakly taken, 03: strongly taken
  val score = RegInit(VecInit(Seq.fill(PSIZE)(1.U(3.W))))//if score>=2,use Gshare, otherwise use BHT
  val history = RegInit(0.U(PBIT.W))//PBIT bits of history predicted results

//query
  for (i<-0 until issueWidth)
  {
    val subaddr = Wire(UInt(PBIT.W))
    val gshare_res = Wire(Bool())
    val pht_res = Wire(Bool())
    val res = Wire(UInt(32.W))
    val used = Wire(Bool())
    val different_result = Wire(Bool())
    val localhistory = Wire(UInt(KBIT.W))

    subaddr := io.qaddr(i)(PBIT+1,2)
    localhistory := LHT(subaddr)
    gshare_res := Gshare_status(subaddr^history)>=2.U
    pht_res := PHT(Cat(subaddr,localhistory))>=2.U
    used:= score(subaddr)>=2
    different_result := (gshare_res=/=pht_res)
    res := Mux(used,gshare_res,pht_res)
    io.out(i) := Cat(localhistory,history,different_result,used,res)
  }

//update
    when (uvalid===true.B){
        val _subaddr = Wire(UInt(PBIT.W))
        val _localhistory = Wire(UInt(KBIT.W))
        val globalhistory = Wire(UInt(PBIT.W))
        val reverted = Wire(Bool())
        val taken = Wire(Bool())
        val _used = Wire(Bool())
        val _different_result = Wire(Bool())
        _subaddr := uaddr(PBIT+1,2)
        _localhistory := ures(PBIT+KBIT+3,PBIT+4)
        globalhistory := ures(PBIT+3,4)
        reverted := ures(0)
        taken := ures(1)
        _used := ures(2)
        different_result := ures(3)
        when (reverted===true.B){
            when(different_result===true.B){
                score(_subaddr):=Mux(used,Mux(score(_subaddr)===0.U,0.U,score(_subaddr)-1.U),Mux(score(_subaddr)===3.U,3.U,score(_subaddr)+1.U))
            }
        }.otherwise{
            when(different_result===true.B){
                score(_subaddr):=Mux(used,Mux(score(_subaddr)===3.U,3.U,score(_subaddr)+1.U),Mux(score(_subaddr)===0.U,0.U,score(_subaddr)-1.U))
            }
        }
        val tkrev = Wire(Bool())
        tkrev := taken^reverted
        val temp = Wire(UInt(PBit.W))
        temp := Gshare_status(_subaddr^globalhistory)
        Gshare_status(_subaddr^globalhistory) := Mux(tkrev,Mux(temp===3.U,3.U,temp+1.U),Mux(temp===0.U,0.U,temp-1.U))
        val temp2=Wire(UInt(KBIT.W))
        temp2 := PHT(Cat(_subaddr,_localhistory))
        PHT(Cat(_subaddr,_localhistory)):=  Mux(tkrev,Mux(temp2===3.U,3.U,temp2+1.U),Mux(temp2===0.U,0.U,temp2-1.U))
        history:= Cat(globalhistory(PBIT-2,0),tkrev)
        LHT(_subaddr) := Cat(_localhistory(KBIT-2,0),tkrev)
    }
}