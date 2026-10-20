//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused

package Op
import chisel3._
object OP_TYPE extends ChiselEnum{
    val OP_NONE,
    OP_ADD,
    OP_SUB,
    OP_AND,
    OP_OR,
    OP_XOR,
    OP_SLL,
    OP_SRL,
    OP_SRA,
    OP_SLT,
    OP_SLTU,
    OP_ADDI,
    OP_ANDI,
    OP_ORI,
    OP_XORI,
    OP_SLLI,
    OP_SRLI,
    OP_SRAI,
    OP_SLTI,
    OP_SLTIU,
    OP_LB,
    OP_LBU,
    OP_LH,
    OP_LHU,
    OP_LW,
    OP_SB,
    OP_SH,
    OP_SW,
    OP_BEQ,
    OP_BGE,
    OP_BGEU,
    OP_BLT,
    OP_BLTU,
    OP_BNE,
    OP_JAL,
    OP_JALR,
    OP_AUIPC,
    OP_LUI,
    OP_MUL,
    OP_MULH,
    OP_MULHSU,
    OP_MULHU,
    OP_DIV,
    OP_DIVU,
    OP_REM,
    OP_REMU = Value
}

class Op extends Bundle{
    val type_ = OP_TYPE()
    val rd=UInt(32.W)
    val r1=UInt(32.W)
    val r2=UInt(32.W)
    val id=UInt(32.W)
}
