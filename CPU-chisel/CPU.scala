package CPU
import chisel3._
import chisel3.util._
import Config.CPUConfig._
import Op._
import Register.Register
import CDB.CDB
import Cache._
import Decoder.Decoder
import Issuer.Issuer
import Executer.Executer
import Committer.Committer
import Predictor.Predictor

class CPU extends Module {
  val io = IO(new AxiIO)
  val frontend = Module(new Frontend)
  val decoder = Module(new Decoder)
  val issuer = Module(new Issuer)
  val executer = Module(new Executer)
  val committer = Module(new Committer)
  val predictor = Module(new Predictor)
  val dcache = Module(new Cache(2))
  val bridge = Module(new AxiBridge)
  io <> bridge.io.axi
  for(i <- 0 until issueWidth) { bridge.io.clients(i) <> frontend.io.memory(i) }
  bridge.io.clients(issueWidth) <> dcache.io.memory
  val regs = RegInit(0.U.asTypeOf(Vec(32, new Register)))
  val broadcasts = Wire(Vec(cdbCount, new CDB))
  for(i <- 0 until ALU_NUM+2) { broadcasts(i) := executer.io.out(i) }
  val head = committer.io.head
  val retire = committer.io.headValid && head.complete
  val flush = retire && (head.op.branch || head.op.jump) && head.nextPc =/= head.predictedPc
  committer.io.retire := retire
  committer.io.flush := flush
  frontend.io.flush := flush
  frontend.io.redirect := head.nextPc
  executer.io.flush := flush
  predictor.io.update := retire && head.op.branch
  predictor.io.updatePc := head.op.pc
  predictor.io.updateTaken := head.taken
  when(retire) {
    assert(!head.op.illegal, "Retiring unsupported/illegal instruction")
    when(head.op.writesRd && head.op.rd =/= 0.U) {
      regs(head.op.rd).value := head.value
      when(regs(head.op.rd).busy && regs(head.op.rd).prod === committer.io.headId) {
        regs(head.op.rd).busy := false.B
      }
    }
  }
  decoder.io.pc := frontend.io.pc
  decoder.io.instruction := frontend.io.instructions
  predictor.io.pc := frontend.io.pc
  issuer.io.enable := !flush
  issuer.io.available := frontend.io.count
  issuer.io.ops := decoder.io.out
  issuer.io.prediction := predictor.io.taken
  issuer.io.regs := regs
  // Retirement bypass matters when a producer leaves the ROB during dispatch.
  when(retire && head.op.writesRd && head.op.rd =/= 0.U) {
    issuer.io.regs(head.op.rd).value := head.value
    when(regs(head.op.rd).busy && regs(head.op.rd).prod === committer.io.headId) {
      issuer.io.regs(head.op.rd).busy := false.B
    }
  }
  issuer.io.robs := committer.io.entries
  issuer.io.cdbs := broadcasts
  issuer.io.tail := committer.io.tail
  issuer.io.robSpace := committer.io.space
  issuer.io.rsSpace := executer.io.space
  frontend.io.consume := issuer.io.count
  frontend.io.control := issuer.io.control
  frontend.io.controlPc := issuer.io.nextPc
  committer.io.count := issuer.io.count
  committer.io.in := issuer.io.rob
  committer.io.cdbs := broadcasts
  executer.io.count := issuer.io.count
  executer.io.in := issuer.io.rs
  executer.io.cdbs := broadcasts
  for(i <- 0 until issueWidth) {
    val op = decoder.io.out(i)
    when(i.U < issuer.io.count && op.writesRd && op.rd =/= 0.U) {
      regs(op.rd).busy := true.B
      regs(op.rd).prod := committer.io.tail + i.U
    }
  }
  // Recovery happens at retirement: all surviving older results are already architectural.
  when(flush) { regs.foreach(_.busy := false.B) }
  regs(0) := 0.U.asTypeOf(new Register)

  // Conservative LSU: address generation is out of order, external memory operations occur at ROB head.
  val pending = RegInit(false.B)
  val memoryHead = committer.io.headValid && head.executed && !head.complete && (head.op.load || head.op.store)
  val word = head.op.type_ === OP_TYPE.OP_LW || head.op.type_ === OP_TYPE.OP_SW
  val half = head.op.type_ === OP_TYPE.OP_LH || head.op.type_ === OP_TYPE.OP_LHU || head.op.type_ === OP_TYPE.OP_SH
  dcache.io.cpu.req.valid := memoryHead && !pending
  dcache.io.cpu.req.bits.addr := head.addr & "hfffffffc".U
  dcache.io.cpu.req.bits.write := head.op.store
  dcache.io.cpu.req.bits.data := head.value << Cat(head.addr(1,0), 0.U(3.W))
  dcache.io.cpu.req.bits.mask := Mux(word, 15.U(4.W), Mux(half, 3.U(4.W), 1.U(4.W))) << head.addr(1,0)
  dcache.io.cpu.resp.ready := pending
  when(dcache.io.cpu.req.fire) {
    assert(!word || head.addr(1,0) === 0.U, "Misaligned word access")
    assert(!half || !head.addr(0), "Misaligned halfword access")
    pending := true.B
  }
  val loadData = dcache.io.cpu.resp.bits >> Cat(head.addr(1,0), 0.U(3.W))
  val loadValue = WireDefault(loadData)
  switch(head.op.type_) {
    is(OP_TYPE.OP_LB) { loadValue := Cat(Fill(24,loadData(7)),loadData(7,0)) }
    is(OP_TYPE.OP_LBU) { loadValue := loadData(7,0) }
    is(OP_TYPE.OP_LH) { loadValue := Cat(Fill(16,loadData(15)),loadData(15,0)) }
    is(OP_TYPE.OP_LHU) { loadValue := loadData(15,0) }
  }
  broadcasts(cdbCount-1) := 0.U.asTypeOf(new CDB)
  broadcasts(cdbCount-1).valid := dcache.io.cpu.resp.fire
  broadcasts(cdbCount-1).id := committer.io.headId
  broadcasts(cdbCount-1).value := Mux(head.op.load, loadValue, head.value)
  broadcasts(cdbCount-1).addr := head.addr
  broadcasts(cdbCount-1).nextPc := head.op.pc + 4.U
  broadcasts(cdbCount-1).complete := true.B
  when(dcache.io.cpu.resp.fire) { pending := false.B }
  when(flush) { assert(!pending, "Cannot flush a retiring memory transaction") }
}
