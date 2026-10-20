//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
//> using options -Xfatal-warnings -Ywarn-unused

package Decoder
import Op._
import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import chisel3.util._
import _root_.circt.stage.ChiselStage


class Decoder extends Module{
    val io=IO(new Bundle{
        val valid = Input(Bool())
        val id = Input(SInt(32.W))
        val opcode = Input(UInt(32.W))
        val out_valid = Output(Bool())
        val out_id = Output(SInt(32.W))
        val out_op = Output(new Op())
    })
    //init
    val valid_reg=RegInit(false.B)
    val opcode_reg=Reg(UInt(32.W))
    //decode
    valid_reg:=io.valid
    opcode_reg:=io.opcode
    out_valid:=valid_reg
    io.out_id:=RegNext(io.id)
    io.out_op.id:=0.U(32.W)
    switch (opcode_reg(6,0)){
        is("0b0110011".U){//R
            io.out_op.rd:=opcode_reg(11,7)
            io.out_op.r1:=opcode_reg(19,15)
            io.out_op.r2:=opcode_reg(24,20)
            switch(opcode_reg(25,25))
            {
                is("0b0".U){
                    switch(opcode_reg(14,12)){
                        is("0b000".U){
                            io.out_op.type_ := Mux(opcode_reg(30),OP_TYPE.OP_SUB,OP_TYPE.OP_ADD)
                        }
                        is("0b111".U){
                            io.out_op.type_ := OP_TYPE.OP_AND
                        }
                        is ("0b110".U){
                            io.out_op.type_ := OP_TYPE.OP_OR
                        }
                        is ("0b100".U){
                            io.out_op.type_ := OP_TYPE.OP_XOR
                        }
                        is ("0b001".U){
                            io.out_op.type_ := OP_TYPE.OP_SLL
                        }
                        is ("0b101".U){
                            io.out_op.type_ := Mux(opcode_reg(30),OP_TYPE.OP_SRA,OP_TYPE.OP_SRL)
                        }
                        is ("0b010".U){
                            io.out_op.type_ := OP_TYPE.OP_SLT
                        }
                        is ("0b011".U){
                            io.out_op.type_ := OP_TYPE.OP_SLTU
                        }
                    }
                }
                is("0b1".U){//M extension
                    switch(opcode_reg(14,12)){
                        is ("0b000".U){//low 32 bits
                            io.out_op.type_ := OP_TYPE.OP_MUL
                        }
                        is ("0b001".U){//signed*signed, high 32 bits
                            io.out_op.type_ := OP_TYPE.OP_MULH
                        }
                        is ("0b010".U){//signed*unsigned, high 32 bits
                            io.out_op.type_ := OP_TYPE.OP_MULHSU
                        }
                        is ("0b011".U){//unsigned*unsigned, high 32 bits
                            io.out_op.type_ := OP_TYPE.OP_MULHU
                        }
                        is ("0b100".U){//signed a/b, round to 0
                            io.out_op.type_ := OP_TYPE.OP_DIV
                        }
                        is ("0b101".U){
                            io.out_op.type_ := OP_TYPE.OP_DIVU
                        }
                        is ("0b110".U){//same sign with a
                            io.out_op.type_ := OP_TYPE.OP_REM
                        }
                        is ("0b111".U){
                            io.out_op.type_ := OP_TYPE.OP_REMU
                        }
                    }
                }
            }
        }
        is("0b0010011".U){//I(*)
            io.out_op.rd:=opcode_reg(11,7)
            io.out_op.r1:=opcode_reg(19,15)
            io.out_op.r2:=opcode_reg(24,20)
            switch(opcode_reg(14,12)){
                is ("0b000".U){
                    io.out_op.type_ := OP_TYPE.OP_ADDI
                }
                is ("0b111".U){
                    io.out_op.type_ := OP_TYPE.OP_ANDI
                }
                is ("0b110".U){
                    io.out_op.type_ := OP_TYPE.OP_ORI
                }
                is ("0b100".U){
                    io.out_op.type_ := OP_TYPE.OP_XORI
                }
                is ("0b001".U){
                    io.out_op.type_ := OP_TYPE.OP_SLLI
                }
                is ("0b101".U){
                    io.out_op.type_ := Mux(opcode_reg(30),OP_TYPE.OP_SRAI,OP_TYPE.OP_SRLI)
                }
                is ("0b010".U){
                    io.out_op.type_ := OP_TYPE.OP_SLTI
                }
                is ("0b011".U){
                    io.out_op.type_ := OP_TYPE.OP_SLTIU
                }
            }
        }
        is("0b0000011".U){//I memory
            io.out_op.rd:=opcode_reg(11,7)
            io.out_op.r1:=opcode_reg(19,15)
            io.out_op.r2:=opcode_reg(24,20)
            switch(opcode_reg(14,12)){
                is ("0b000".U){
                    io.out_op.type_ := OP_TYPE.OP_LB
                }
                is ("0b100".U){
                    io.out_op.type_ := OP_TYPE.OP_LBU
                }
                is ("0b001".U){
                    io.out_op.type_ := OP_TYPE.OP_LH
                }
                is ("0b101".U){
                    io.out_op.type_ := OP_TYPE.OP_LHU
                }
                is ("0b010".U){
                    io.out_op.type_ := OP_TYPE.OP_LW
                }
            }
        }
        is("0b0100011".U){//S
            io.out_op.rd:=Cat(opcode_reg(31,25),opcode_reg(11,7))
            io.out_op.r1:=opcode_reg(19,15)
            io.out_op.r2:=opcode_reg(24,20)
            switch(opcode_reg(14,12)){
                is ("0b000".U){
                    io.out_op.type_ := OP_TYPE.OP_SB
                }
                is ("0b001".U){
                    io.out_op.type_ := OP_TYPE.OP_SH
                }
                is ("0b010".U){
                    io.out_op.type_ := OP_TYPE.OP_SW
                }
            }
        }
        is("0b1100011".U){//B
            io.out_op.rd:=Cat(opcode_reg(31,31),opcode_reg(7,7),opcode_reg(30,25),opcode_reg(11,8),false.B)
            io.out_op.r1:=opcode_reg(19,15)
            io.out_op.r2:=opcode_reg(24,20)
            switch(opcode_reg(14,12)){
                is ("0b000".U){
                    io.out_op.type_ := OP_TYPE.OP_BEQ
                }
                is ("0b101".U){
                    io.out_op.type_ := OP_TYPE.OP_BGE
                }
                is ("0b111".U){
                    io.out_op.type_ := OP_TYPE.OP_BGEU
                }
                is ("0b100".U){
                    io.out_op.type_ := OP_TYPE.OP_BLT
                }
                is ("0b110".U){
                    io.out_op.type_ := OP_TYPE.OP_BLTU
                }
                is ("0b001".U){
                    io.out_op.type_ := OP_TYPE.OP_BNE
                }
            }
        }
        is("0b1101111".U){//JAL
            io.out_op.rd:=opcode_reg(11,7)
            io.out_op.r1:=Cat(opcode_reg(31,31),opcode_reg(19,12),opcode_reg(20,20),opcode_reg(30,21))
            io.out_op.r2:=0.U(32.W)
            io.out_op.type_ := OP_TYPE.OP_JAL
        }
        is ("0b1100111".U){//JALR
            io.out_op.rd:=opcode_reg(11,7)
            io.out_op.r1:=opcode_reg(19,15)
            io.out_op.r2:=opcode_reg(24,20)
            io.out_op.type_ := OP_TYPE.OP_JALR
        }
        is("0b0010111".U){//AUIPC
            io.out_op.rd:=opcode_reg(11,7)
            io.out_op.r1:=Cat(opcode_reg(31,12),0.U(12.W))
            io.out_op.r2:=0.U(32.W)
            io.out_op.type_ := OP_TYPE.OP_AUIPC
        }
        is("0b0110111".U){//LUI
            io.out_op.rd:=opcode_reg(11,7)
            io.out_op.r1:=Cat(opcode_reg(31,12),0.U(12.W))
            io.out_op.r2:=0.U(32.W)
            io.out_op.type_ := OP_TYPE.OP_LUI
        }
    }
}
