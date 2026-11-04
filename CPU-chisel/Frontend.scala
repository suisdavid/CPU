package CPU
import chisel3._
import chisel3.util._
import Config.CPUConfig._
import Cache._

/** A held fetch packet supplies up to issueWidth consecutive instructions.
  * Flush discards the packet but drains all existing requests before restarting.
  */
class Frontend extends Module {
  val io = IO(new Bundle {
    val memory = Vec(issueWidth, new MemoryPort)
    val consume = Input(UInt(log2Ceil(issueWidth+1).W))
    val control = Input(Bool())
    val controlPc = Input(UInt(32.W))
    val flush = Input(Bool())
    val redirect = Input(UInt(32.W))
    val count = Output(UInt(log2Ceil(issueWidth+1).W))
    val pc = Output(Vec(issueWidth, UInt(32.W)))
    val instructions = Output(Vec(issueWidth, UInt(32.W)))
  })
  val caches = Seq.fill(issueWidth)(Module(new Cache(2+bankBits)))
  for(b <- 0 until issueWidth) { io.memory(b) <> caches(b).io.memory }
  val nextPc = RegInit(0.U(32.W))
  val activePc = Reg(UInt(32.W))
  val bufferPc = Reg(UInt(32.W))
  val active = RegInit(false.B)
  val killed = RegInit(false.B)
  val sent = RegInit(VecInit(Seq.fill(issueWidth)(false.B)))
  val done = RegInit(VecInit(Seq.fill(issueWidth)(false.B)))
  val words = Reg(Vec(issueWidth, UInt(32.W)))
  val count = RegInit(0.U(log2Ceil(issueWidth+1).W))
  def bank(pc: UInt): UInt = if(bankBits == 0) 0.U else pc(2+bankBits-1,2)
  def bankAt(pc: UInt, lane: Int): UInt =
    if(bankBits == 0) 0.U else (bank(pc) + lane.U)(bankBits-1,0)
  io.count := count
  for(i <- 0 until issueWidth) {
    io.pc(i) := bufferPc + (4*i).U
    io.instructions(i) := words(bankAt(bufferPc,i))
  }
  when(!active && count === 0.U && !io.flush) {
    active := true.B; activePc := nextPc; killed := false.B
    sent.foreach(_ := false.B); done.foreach(_ := false.B)
  }
  for(b <- 0 until issueWidth) {
    val port = caches(b).io.cpu
    port.req.valid := active && !sent(b)
    port.req.bits := 0.U.asTypeOf(new MemoryRequest)
    port.req.bits.addr := (activePc & (~(4*issueWidth-1L) & 0xffffffffL).U(32.W)) + (4*b).U +
      Mux(b.U < bank(activePc), (4*issueWidth).U, 0.U)
    port.resp.ready := active && !done(b)
    when(port.req.fire) { sent(b) := true.B }
    when(port.resp.fire) { done(b) := true.B; words(b) := port.resp.bits }
  }
  when(active && done.asUInt.andR) {
    active := false.B
    when(!killed && !io.flush) {
      bufferPc := activePc; count := issueWidth.U; nextPc := activePc + (4*issueWidth).U
    }
  }
  when(io.consume =/= 0.U) {
    assert(io.consume <= count)
    count := count - io.consume
    bufferPc := bufferPc + (io.consume << 2)
    when(io.control) { count := 0.U; nextPc := io.controlPc }
  }
  when(io.flush) {
    count := 0.U; nextPc := io.redirect
    when(active) { killed := true.B }
  }
}
