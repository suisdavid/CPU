package CPU

import chisel3._
import chisel3.util._

/** Ordered AXI4-Lite reads. The last requester is D-cache; others are I-cache banks. */
class ReadArbiter(val cacheCount: Int, val depth: Int = 4) extends Module {
  require(cacheCount >= 2)
  require(depth >= 2 && isPow2(depth))
  val io = IO(new Bundle {
    // A cache holds its miss until refill. Each owner may reserve only one read.
    val request = Input(Vec(cacheCount, Valid(UInt(32.W))))
    val redirect = Input(Bool())
    val refill = Output(Vec(cacheCount, Valid(UInt(32.W))))
    val araddr = Output(UInt(32.W))
    val arvalid = Output(Bool())
    val arready = Input(Bool())
    val rdata = Input(UInt(32.W))
    val rvalid = Input(Bool())
    val rready = Output(Bool())
  })

  private val dataOwner = cacheCount - 1
  private val ownerWidth = log2Ceil(cacheCount)
  private val ptrWidth = log2Ceil(depth)
  val busy = RegInit(VecInit(Seq.fill(cacheCount)(false.B)))

  // The AR holding register reserves a FIFO slot before asserting ARVALID.
  // It remains stable under backpressure, including across a redirect.
  val arPending = RegInit(false.B)
  val arAddr = Reg(UInt(32.W))
  val arOwner = Reg(UInt(ownerWidth.W))
  val arDiscard = RegInit(false.B)
  val owners = Reg(Vec(depth, UInt(ownerWidth.W)))
  val discard = RegInit(VecInit(Seq.fill(depth)(false.B)))
  val head = RegInit(0.U(ptrWidth.W))
  val tail = RegInit(0.U(ptrWidth.W))
  val count = RegInit(0.U(log2Ceil(depth + 1).W))

  io.arvalid := arPending
  io.araddr := arAddr
  io.rready := count =/= 0.U
  val arFire = io.arvalid && io.arready
  val rFire = io.rvalid && io.rready
  for (i <- 0 until cacheCount) {
    io.refill(i).bits := io.rdata
    io.refill(i).valid := rFire && owners(head) === i.U &&
      (if (i == dataOwner) true.B else !discard(head) && !io.redirect)
  }

  // Never reset the FIFO/busy bits on redirect: accepted reads must drain in order.
  // Mark all existing slots; enqueue below overwrites unused slots with fresh metadata.
  when(io.redirect) {
    discard.foreach(_ := true.B)
    when(arPending && arOwner =/= dataOwner.U) { arDiscard := true.B }
  }
  when(arFire) {
    owners(tail) := arOwner
    discard(tail) := arDiscard || (io.redirect && arOwner =/= dataOwner.U)
    tail := tail + 1.U
    arPending := false.B
  }
  when(rFire) {
    busy(owners(head)) := false.B
    head := head + 1.U
  }
  when(arFire =/= rFire) { count := Mux(arFire, count + 1.U, count - 1.U) }

  // D-cache first, then the lowest numbered I-cache bank. Busy includes the AR
  // holding register, so a persistent out_neednew cannot enqueue the same miss twice.
  val selected = WireDefault(false.B)
  val selectedOwner = WireDefault(0.U(ownerWidth.W))
  val selectedAddr = WireDefault(0.U(32.W))
  for (i <- (0 until dataOwner).reverse ++ Seq(dataOwner)) {
    when(io.request(i).valid && !busy(i) &&
      (if (i == dataOwner) true.B else !io.redirect)) {
      selected := true.B
      selectedOwner := i.U
      selectedAddr := io.request(i).bits
    }
  }
  // Count both accepted requests and the reserved AR slot. A response releases
  // a credit immediately; replacing a handshaken AR permits consecutive requests.
  val creditAvailable = (count +& arPending.asUInt) < depth.U || rFire
  when((!arPending || arFire) && creditAvailable && selected) {
    arPending := true.B
    arAddr := selectedAddr
    arOwner := selectedOwner
    arDiscard := false.B
    busy(selectedOwner) := true.B
  }
}
