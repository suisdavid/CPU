package Decoder
import chisel3._
import chisel3.util._
import Config.CPUConfig._
import Op._

/** Combinational decode: frontend owns the valid/ready buffer. */
class Decoder extends Module {
  val io = IO(new Bundle {
    val pc = Input(Vec(issueWidth, UInt(32.W)))
    val instruction = Input(Vec(issueWidth, UInt(32.W)))
    val out = Output(Vec(issueWidth, new Op))
  })
  for (i <- 0 until issueWidth) {
    val x = io.instruction(i)
    val o = io.out(i)
    o := 0.U.asTypeOf(new Op)
    o.type_ := OP_TYPE.OP_NONE
    o.illegal := true.B
    o.pc := io.pc(i)
    o.instruction := x
    o.rd := x(11, 7)
    o.r1 := x(19, 15)
    o.r2 := x(24, 20)
    val immI = Cat(Fill(20, x(31)), x(31,20))
    val immS = Cat(Fill(20, x(31)), x(31,25), x(11,7))
    val immB = Cat(Fill(19, x(31)), x(31), x(7), x(30,25), x(11,8), 0.U(1.W))
    val immJ = Cat(Fill(11, x(31)), x(31), x(19,12), x(20), x(30,21), 0.U(1.W))
    def legal(t: OP_TYPE.Type): Unit = { o.type_ := t; o.illegal := false.B }
    switch(x(6,0)) {
      is("b0110011".U) {
        o.uses1 := true.B; o.uses2 := true.B; o.writesRd := true.B
        when(x(31,25) === 1.U) {
          o.mul := !x(14); o.div := x(14)
          switch(x(14,12)) {
            is(0.U) { legal(OP_TYPE.OP_MUL) }
            is(1.U) { legal(OP_TYPE.OP_MULH) }
            is(2.U) { legal(OP_TYPE.OP_MULHSU) }
            is(3.U) { legal(OP_TYPE.OP_MULHU) }
            is(4.U) { legal(OP_TYPE.OP_DIV) }
            is(5.U) { legal(OP_TYPE.OP_DIVU) }
            is(6.U) { legal(OP_TYPE.OP_REM) }
            is(7.U) { legal(OP_TYPE.OP_REMU) }
          }
        }.elsewhen(x(31,25) === 0.U) {
          switch(x(14,12)) {
            is(0.U) { legal(OP_TYPE.OP_ADD) }
            is(1.U) { legal(OP_TYPE.OP_SLL) }
            is(2.U) { legal(OP_TYPE.OP_SLT) }
            is(3.U) { legal(OP_TYPE.OP_SLTU) }
            is(4.U) { legal(OP_TYPE.OP_XOR) }
            is(5.U) { legal(OP_TYPE.OP_SRL) }
            is(6.U) { legal(OP_TYPE.OP_OR) }
            is(7.U) { legal(OP_TYPE.OP_AND) }
          }
        }.elsewhen(x(31,25) === 32.U) {
          switch(x(14,12)) {
            is(0.U) { legal(OP_TYPE.OP_SUB) }
            is(5.U) { legal(OP_TYPE.OP_SRA) }
          }
        }
      }
      is("b0010011".U) {
        o.uses1 := true.B; o.writesRd := true.B; o.imm := immI
        switch(x(14,12)) {
          is(0.U) { legal(OP_TYPE.OP_ADDI) }
          is(2.U) { legal(OP_TYPE.OP_SLTI) }
          is(3.U) { legal(OP_TYPE.OP_SLTIU) }
          is(4.U) { legal(OP_TYPE.OP_XORI) }
          is(6.U) { legal(OP_TYPE.OP_ORI) }
          is(7.U) { legal(OP_TYPE.OP_ANDI) }
          is(1.U) { when(x(31,25) === 0.U) { legal(OP_TYPE.OP_SLLI) } }
          is(5.U) {
            when(x(31,25) === 0.U) { legal(OP_TYPE.OP_SRLI) }
            when(x(31,25) === 32.U) { legal(OP_TYPE.OP_SRAI) }
          }
        }
      }
      is("b0000011".U) {
        o.uses1 := true.B; o.writesRd := true.B; o.load := true.B; o.imm := immI
        switch(x(14,12)) {
          is(0.U) { legal(OP_TYPE.OP_LB) }; is(1.U) { legal(OP_TYPE.OP_LH) }
          is(2.U) { legal(OP_TYPE.OP_LW) }; is(4.U) { legal(OP_TYPE.OP_LBU) }
          is(5.U) { legal(OP_TYPE.OP_LHU) }
        }
      }
      is("b0100011".U) {
        o.uses1 := true.B; o.uses2 := true.B; o.store := true.B; o.imm := immS
        switch(x(14,12)) {
          is(0.U) { legal(OP_TYPE.OP_SB) }; is(1.U) { legal(OP_TYPE.OP_SH) }
          is(2.U) { legal(OP_TYPE.OP_SW) }
        }
      }
      is("b1100011".U) {
        o.uses1 := true.B; o.uses2 := true.B; o.branch := true.B; o.imm := immB
        switch(x(14,12)) {
          is(0.U) { legal(OP_TYPE.OP_BEQ) }; is(1.U) { legal(OP_TYPE.OP_BNE) }
          is(4.U) { legal(OP_TYPE.OP_BLT) }; is(5.U) { legal(OP_TYPE.OP_BGE) }
          is(6.U) { legal(OP_TYPE.OP_BLTU) }; is(7.U) { legal(OP_TYPE.OP_BGEU) }
        }
      }
      is("b1101111".U) { legal(OP_TYPE.OP_JAL); o.jump := true.B; o.writesRd := true.B; o.imm := immJ }
      is("b1100111".U) {
        when(x(14,12) === 0.U) { legal(OP_TYPE.OP_JALR) }
        o.jump := true.B; o.writesRd := true.B; o.uses1 := true.B; o.imm := immI
      }
      is("b0110111".U) { legal(OP_TYPE.OP_LUI); o.writesRd := true.B; o.imm := Cat(x(31,12),0.U(12.W)) }
      is("b0010111".U) { legal(OP_TYPE.OP_AUIPC); o.writesRd := true.B; o.imm := Cat(x(31,12),0.U(12.W)) }
      // Barriers are harmless here: memory operations retire serially; no self-modifying code support.
      is("b0001111".U) { when(x(14,12) === 0.U) { legal(OP_TYPE.OP_NONE) } }
    }
  }
}
