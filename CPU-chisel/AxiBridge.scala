package CPU
import chisel3._
import chisel3.util._
import Config.CPUConfig._
import Cache._

class AxiIO extends Bundle {
  val araddr = Output(UInt(32.W)); val arvalid = Output(Bool()); val arready = Input(Bool())
  val rdata = Input(UInt(32.W)); val rresp = Input(UInt(2.W)); val rvalid = Input(Bool()); val rready = Output(Bool())
  val awaddr = Output(UInt(32.W)); val awvalid = Output(Bool()); val awready = Input(Bool())
  val wdata = Output(UInt(32.W)); val wstrb = Output(UInt(4.W)); val wvalid = Output(Bool()); val wready = Input(Bool())
  val bresp = Input(UInt(2.W)); val bvalid = Input(Bool()); val bready = Output(Bool())
}

/** One outstanding transaction. AW/W handshake independently and remain stable until accepted. */
class AxiBridge extends Module {
  val io = IO(new Bundle {
    val clients = Flipped(Vec(issueWidth+1, new MemoryPort))
    val axi = new AxiIO
  })
  val idle :: readAddress :: readData :: writeData :: writeResponse :: deliver :: Nil = Enum(6)
  val state = RegInit(idle)
  val owner = Reg(UInt(log2Ceil(issueWidth+1).W))
  val request = Reg(new MemoryRequest)
  val response = Reg(UInt(32.W))
  val awDone = RegInit(false.B)
  val wDone = RegInit(false.B)
  val candidates = VecInit(io.clients.map(_.req.valid))
  val winner = Mux(candidates(issueWidth), issueWidth.U, PriorityEncoder(candidates))
  for(i <- 0 to issueWidth) {
    io.clients(i).req.ready := state === idle && winner === i.U
    io.clients(i).resp.valid := state === deliver && owner === i.U
    io.clients(i).resp.bits := response
  }
  when(state === idle && candidates.asUInt.orR) {
    owner := winner; request := io.clients(winner).req.bits
    awDone := false.B; wDone := false.B
    state := Mux(io.clients(winner).req.bits.write, writeData, readAddress)
  }
  io.axi.araddr := request.addr
  io.axi.arvalid := state === readAddress
  io.axi.rready := state === readData
  io.axi.awaddr := request.addr
  io.axi.awvalid := state === writeData && !awDone
  io.axi.wdata := request.data
  io.axi.wstrb := request.mask
  io.axi.wvalid := state === writeData && !wDone
  io.axi.bready := state === writeResponse
  when(io.axi.arvalid && io.axi.arready) { state := readData }
  when(io.axi.rvalid && io.axi.rready) {
    assert(io.axi.rresp === 0.U, "AXI read error")
    response := io.axi.rdata; state := deliver
  }
  when(state === writeData) {
    when(io.axi.awready) { awDone := true.B }
    when(io.axi.wready) { wDone := true.B }
    when((awDone || io.axi.awready) && (wDone || io.axi.wready)) { state := writeResponse }
  }
  when(io.axi.bvalid && io.axi.bready) {
    assert(io.axi.bresp === 0.U, "AXI write error")
    response := 0.U; state := deliver
  }
  when(state === deliver && io.clients(owner).resp.ready) { state := idle }
}
