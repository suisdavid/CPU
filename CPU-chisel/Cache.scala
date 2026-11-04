package Cache
import chisel3._
import chisel3.util._
import Config.CPUConfig._
import cpu2026.SRAM

class MemoryRequest extends Bundle {
  val addr = UInt(32.W)
  val write = Bool()
  val data = UInt(32.W)
  val mask = UInt(4.W)
}
class MemoryPort extends Bundle {
  val req = Decoupled(new MemoryRequest)
  val resp = Flipped(Decoupled(UInt(32.W)))
}

/** One word per line, synchronous SRAM data. Write-through/no-write-allocate keeps MMIO and retirement precise.
  * offsetBits includes the bank bits for I-cache; D-cache uses offsetBits=2.
  */
class Cache(val offsetBits: Int) extends Module {
  val io = IO(new Bundle {
    val cpu = Flipped(new MemoryPort)
    val memory = new MemoryPort
  })
  val tagBits = 32 - setBIT - offsetBits
  val waysBits = math.max(1, log2Ceil(lineNum))
  val valid = RegInit(VecInit(Seq.fill(setSIZE)(VecInit(Seq.fill(lineNum)(false.B)))))
  val tags = Reg(Vec(setSIZE, Vec(lineNum, UInt(tagBits.W))))
  val victim = RegInit(VecInit(Seq.fill(setSIZE)(0.U(waysBits.W))))
  val data = Seq.fill(lineNum)(Module(new SRAM(setSIZE, 32, 8)))
  val idle :: lookup :: send :: waitResponse :: reply :: Nil = Enum(5)
  val state = RegInit(idle)
  val request = Reg(new MemoryRequest)
  val response = Reg(UInt(32.W))
  val chosen = Reg(UInt(waysBits.W))
  val writeHit = Reg(Bool())
  val index = request.addr(offsetBits+setBIT-1, offsetBits)
  val tag = request.addr(31, offsetBits+setBIT)
  val cacheable = request.addr(31,28) === 0.U
  val hits = VecInit((0 until lineNum).map(w => valid(index)(w) && tags(index)(w) === tag && cacheable))
  val invalid = VecInit((0 until lineNum).map(w => !valid(index)(w)))
  val hitWay = if(lineNum == 1) 0.U else PriorityEncoder(hits)
  val freeWay = if(lineNum == 1) 0.U else PriorityEncoder(invalid)
  io.cpu.req.ready := state === idle
  io.cpu.resp.valid := state === reply
  io.cpu.resp.bits := response
  io.memory.req.valid := state === send
  io.memory.req.bits := request
  io.memory.resp.ready := state === waitResponse
  for(w <- 0 until lineNum) {
    data(w).clk := clock
    data(w).en := false.B; data(w).we := false.B
    data(w).addr := index; data(w).wmask := 15.U; data(w).wdata := 0.U
    when(io.cpu.req.fire) {
      data(w).en := true.B
      data(w).addr := io.cpu.req.bits.addr(offsetBits+setBIT-1, offsetBits)
    }
  }
  when(io.cpu.req.fire) { request := io.cpu.req.bits; state := lookup }
  when(state === lookup) {
    chosen := Mux(hits.asUInt.orR, hitWay, Mux(invalid.asUInt.orR, freeWay, victim(index)))
    writeHit := hits.asUInt.orR
    when(!request.write && hits.asUInt.orR) {
      response := Mux1H(hits, data.map(_.rdata)); state := reply
    }.otherwise { state := send }
  }
  when(io.memory.req.fire) { state := waitResponse }
  when(io.memory.resp.fire) {
    response := io.memory.resp.bits
    state := reply
    when(cacheable && (!request.write || writeHit)) {
      for(w <- 0 until lineNum) {
        when(chosen === w.U) {
          data(w).en := true.B; data(w).we := true.B
          data(w).wdata := Mux(request.write, request.data, io.memory.resp.bits)
          data(w).wmask := Mux(request.write, request.mask, 15.U)
          tags(index)(w) := tag; valid(index)(w) := true.B
        }
      }
      when(!request.write) { victim(index) := Mux(chosen === (lineNum-1).U, 0.U, chosen+1.U) }
    }
  }
  when(io.cpu.resp.fire) { state := idle }
}
