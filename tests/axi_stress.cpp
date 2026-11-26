// Independent AXI4-Lite slave: separate AW/W stalls, delayed held responses,
// and stability checks on every stalled master channel. Uses the OJ input format.
#include "Vstudent_top.h"
#include "verilated.h"
#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <random>
#include <sstream>
#include <stdexcept>
#include <string>
#include <unordered_map>

static void require(bool condition, const char* message) {
  if (!condition) throw std::runtime_error(message);
}
int main(int argc, char** argv) {
  try {
    Verilated::commandArgs(argc, argv);
    std::mt19937 random(argc > 1 ? std::stoul(argv[1]) : 1);
    std::string magic; int version, latency; uint64_t max_cycles;
    std::cin >> magic >> version >> max_cycles >> latency;
    require(magic == "CPU2026-OJ" && version == 1, "bad input header");
    std::unordered_map<uint32_t, uint8_t> mem;
    std::string line; uint32_t addr = 0;
    while (std::getline(std::cin, line)) {
      auto comment = line.find_first_of("#/");
      if (comment != std::string::npos) line.resize(comment);
      std::istringstream words(line); std::string word;
      while (words >> word) {
        if (word[0] == '@') addr = std::stoul(word.substr(1), nullptr, 16);
        else mem[addr++] = std::stoul(word, nullptr, 16);
      }
    }
    Vstudent_top d;
    d.reset = 1; d.clock = 0;
    d.arready = d.awready = d.wready = d.rvalid = d.bvalid = 0;
    d.rdata = d.rresp = d.bresp = 0;
    for(int i=0;i<5;++i) { d.clock=0; d.eval(); d.clock=1; d.eval(); }
    d.reset = 0;
    bool ar_wait=false, aw_wait=false, w_wait=false;
    uint32_t old_ar=0, old_aw=0, old_wdata=0; unsigned old_mask=0;
    bool read_pending=false, have_aw=false, have_w=false, write_pending=false;
    uint32_t read_addr=0, write_addr=0, write_data=0; unsigned write_mask=0;
    uint64_t read_due=0, write_due=0;
    bool exit_pending=false; uint32_t exit_value=0;
    uint64_t separated=0;
    for(uint64_t cycle=0;cycle<max_cycles;++cycle) {
      // Long, independent backpressure; neither ready depends on valid.
      d.arready = !read_pending && random()%4 == 0;
      d.awready = !have_aw && !write_pending && random()%5 == 0;
      d.wready = !have_w && !write_pending && random()%3 == 0;
      d.rvalid = read_pending && cycle >= read_due;
      d.bvalid = write_pending && cycle >= write_due;
      if(d.rvalid) {
        d.rdata=0; for(int j=0;j<4;++j) d.rdata |= uint32_t(mem[read_addr+j]) << (8*j);
      }
      d.clock=0; d.eval();
      require(!ar_wait || (d.arvalid && d.araddr==old_ar), "AR changed while stalled");
      require(!aw_wait || (d.awvalid && d.awaddr==old_aw), "AW changed while stalled");
      require(!w_wait || (d.wvalid && d.wdata==old_wdata && d.wstrb==old_mask), "W changed while stalled");
      ar_wait = d.arvalid && !d.arready; old_ar=d.araddr;
      aw_wait = d.awvalid && !d.awready; old_aw=d.awaddr;
      w_wait = d.wvalid && !d.wready; old_wdata=d.wdata; old_mask=d.wstrb;
      const bool ar=d.arvalid&&d.arready, aw=d.awvalid&&d.awready, w=d.wvalid&&d.wready;
      const bool r=d.rvalid&&d.rready, b=d.bvalid&&d.bready;
      bool exit_now=b&&exit_pending;
      if(r) read_pending=false;
      if(b) { write_pending=false; exit_pending=false; }
      if(ar) {
        require(!read_pending && !(d.araddr&3) && d.araddr<0x10000000, "invalid/duplicate AR");
        read_pending=true; read_addr=d.araddr; read_due=cycle+latency+1+random()%19;
      }
      if(aw) { require(!have_aw, "duplicate AW"); have_aw=true; write_addr=d.awaddr; }
      if(w) { require(!have_w, "duplicate W"); have_w=true; write_data=d.wdata; write_mask=d.wstrb; }
      if(aw != w) ++separated;
      if(have_aw && have_w) {
        require(!write_pending && !(write_addr&3), "bad write transaction");
        if(write_addr==0x80000000) {
          require(write_mask==15, "partial MMIO exit"); exit_pending=true; exit_value=write_data;
        } else {
          require(write_addr<0x10000000, "write outside RAM");
          for(int j=0;j<4;++j) if(write_mask&(1<<j)) mem[write_addr+j]=write_data>>(8*j);
        }
        have_aw=have_w=false; write_pending=true; write_due=cycle+latency+1+random()%19;
      }
      d.clock=1; d.eval();
      if(exit_now) {
        std::cout << exit_value << '\n';
        std::cerr << "CPU2026 cycles=" << cycle+1 << "\nAXI separated_handshakes=" << separated << '\n';
        d.final(); return 0;
      }
    }
    throw std::runtime_error("cycle limit exceeded");
  } catch(const std::exception& e) { std::cerr << e.what() << '\n'; return 1; }
}
