package cpu2026

import CPU.ReadArbiter
import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import scala.collection.mutable

/** Protocol checks only: no CPU performance measurements. */
object ReadArbiterTests extends App {
  test(new ReadArbiter(5)) { d =>
    case class Read(owner: Int, addr: BigInt, var discard: Boolean)
    val pending = mutable.Queue.empty[Read]
    val requests = Array.fill[Option[BigInt]](5)(None)
    var heldAR: Option[Read] = None
    var serial = 0
    var accepted = 0
    var returned = 0
    var discarded = 0
    var simultaneous = 0
    var maxOutstanding = 0
    def request(owner: Int): Unit = {
      serial += 1
      requests(owner) = Some(BigInt(serial * 256 + owner * 4))
    }
    def tick(arReady: Boolean = true, respond: Boolean = false,
             redirect: Boolean = false): Unit = {
      for (i <- 0 until 5) {
        d.io.request(i).valid.poke(requests(i).nonEmpty.B)
        d.io.request(i).bits.poke(requests(i).getOrElse(BigInt(0)).U)
      }
      d.io.redirect.poke(redirect.B)
      d.io.arready.poke(arReady.B)
      val response = if (respond) pending.headOption else None
      val data = response.map(r => r.addr ^ BigInt("a5a50000", 16)).getOrElse(BigInt(0))
      d.io.rvalid.poke(response.nonEmpty.B)
      d.io.rdata.poke(data.U)
      val arValid = d.io.arvalid.peek().litToBoolean
      val addr = d.io.araddr.peek().litValue
      val owner = ((addr >> 2) & 7).toInt
      heldAR.foreach { old => assert(arValid && addr == old.addr, "stalled AR changed") }
      d.io.rready.expect(pending.nonEmpty.B)
      val deliver = response.filter(r => r.owner == 4 || (!r.discard && !redirect))
      for (i <- 0 until 5) {
        d.io.refill(i).valid.expect(deliver.exists(_.owner == i).B)
        if (deliver.exists(_.owner == i)) d.io.refill(i).bits.expect(data.U)
      }
      // Duplicate prevention includes a response completing on this very edge.
      if (arValid) {
        assert(owner < 5 && !pending.exists(_.owner == owner), "duplicate outstanding owner")
        assert(pending.size + 1 <= 4, "AR has no reserved credit")
      }
      if (redirect) {
        pending.filter(_.owner != 4).foreach(_.discard = true)
        heldAR.filter(_.owner != 4).foreach(_.discard = true)
      }
      if (response.nonEmpty) {
        val old = pending.dequeue()
        returned += 1
        if (deliver.nonEmpty) {
          assert(requests(old.owner).contains(old.addr), "response belongs to an old request")
          requests(old.owner) = None
        } else discarded += 1
      }
      if (arValid) {
        val read = Read(owner, addr, heldAR.exists(_.discard) || (redirect && owner != 4))
        if (arReady) {
          pending.enqueue(read)
          accepted += 1
          if (response.nonEmpty) simultaneous += 1
          heldAR = None
        } else heldAR = Some(read)
      }
      maxOutstanding = maxOutstanding.max(pending.size)
      d.clock.step()
      if (redirect) for (i <- 0 until 4) requests(i) = None
    }
    def drain(): Unit = {
      var cycles = 0
      while (requests.exists(_.nonEmpty) || pending.nonEmpty || heldAR.nonEmpty ||
             d.io.arvalid.peek().litToBoolean) {
        tick(respond = true)
        cycles += 1
        assert(cycles < 100, "read path did not drain")
      }
      tick()
    }

    // Four cold instruction misses issue on four consecutive cycles before any R.
    for (i <- 0 until 4) request(i)
    tick()
    for (i <- 0 until 4) {
      d.io.arvalid.expect(true.B)
      d.io.araddr.expect(requests(i).get.U)
      tick()
    }
    assert(pending.size == 4)
    request(4)
    for (_ <- 0 until 5) { d.io.arvalid.expect(false.B); tick() }
    tick(respond = true) // release one credit, reserve D-cache
    d.io.arvalid.expect(true.B)
    d.io.araddr.expect(requests(4).get.U)
    tick(respond = true) // simultaneous enqueue/dequeue
    drain()

    // D-cache wins arbitration. A stalled I-cache AR survives repeated redirects;
    // a fresh request for that bank must wait until its stale response drains.
    request(0); request(4)
    tick()
    d.io.araddr.expect(requests(4).get.U)
    tick()
    val staleAddr = requests(0).get
    tick(arReady = false, redirect = true)
    request(0)
    for (_ <- 0 until 3) tick(arReady = false)
    tick(arReady = false, respond = true, redirect = true) // D response must survive
    request(0)
    d.io.araddr.expect(staleAddr.U)
    tick() // stale AR finally accepted
    tick(respond = true) // drop it, keeping the fresh miss pending
    drain()

    // Flush all four occupied entries, including an R on the redirect edge.
    for (i <- 0 until 4) request(i)
    tick(); for (_ <- 0 until 4) tick()
    tick(respond = true, redirect = true)
    for (i <- 0 until 5) request(i)
    drain()

    // Immediate responses, repeated pointer wraps, random stalls, I/D contention,
    // and consecutive redirects. The scoreboard tracks owners and stale payloads.
    val random = new scala.util.Random(20261128L)
    for (_ <- 0 until 3000) {
      for (i <- 0 until 5) if (requests(i).isEmpty && random.nextInt(4) == 0) request(i)
      tick(arReady = random.nextInt(3) != 0, respond = random.nextBoolean(),
        redirect = random.nextInt(13) == 0)
    }
    drain()
    assert(accepted == returned && accepted > 100)
    assert(discarded > 0 && simultaneous > 0 && maxOutstanding == 4)
    println(s"PASS read arbiter: $accepted ordered responses, $discarded discarded, " +
      s"$simultaneous simultaneous AR/R; backpressure, redirects, saturation and wraparound")
  }
}
