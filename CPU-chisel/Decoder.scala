//> using scala 2.13.12
//> using jvm 17
//> using dep org.chipsalliance::chisel:6.4.0
//> using plugin org.chipsalliance:::chisel-plugin:6.4.0
//> using dep edu.berkeley.cs::chiseltest:6.0.0
//> using options -unchecked -deprecation -feature -language:reflectiveCalls
// Unused imports in the draft are not compilation errors.

package Decoder
import Op._
import Config.CPUConfig._
import chisel3._
import chiseltest._
import chiseltest.RawTester.test
import chisel3.util._
import _root_.circt.stage.ChiselStage
import Config.CPUConfig.issueWidth


class Decoder extends Module{
    val io=IO(new Bundle{
        val clear_valid = Input(Bool())
        val valid_num = Input(UInt(32.W))
        val id = Input(Vec(issueWidth, UInt(32.W)))
        val opcode = Input(Vec(issueWidth, UInt(32.W)))
        val out_num = Output(UInt(32.W))
        val out_id = Output(Vec(issueWidth, UInt(32.W)))
        val out_op = Output(Vec(issueWidth, new Op))
    })
    //init
    val valid_num_reg=RegInit(0.U(32.W))
    val opcode_reg=Reg(Vec(issueWidth,UInt(32.W)))
    //decode
    valid_num_reg:=Mux(io.clear_valid,0.U,io.valid_num)
    io.out_num:=Mux(io.clear_valid,0.U,valid_num_reg)
    for (i<-0 until issueWidth){
        opcode_reg(i):=io.opcode(i)
        io.out_id(i):=RegNext(io.id(i))
        io.out_op(i):=0.U.asTypeOf(new Op)
        io.out_op(i).type_ :=OP_TYPE.OP_NONE
        switch (opcode_reg(i)(6,0)){
            is("b0110011".U){//R
                io.out_op(i).rd:=opcode_reg(i)(11,7)
                io.out_op(i).r1:=opcode_reg(i)(19,15)
                io.out_op(i).r2:=opcode_reg(i)(24,20)
                switch(opcode_reg(i)(25,25))
                {
                    is("b0".U){
                        switch(opcode_reg(i)(14,12)){
                            is("b000".U){
                                io.out_op(i).type_ := Mux(opcode_reg(i)(30),OP_TYPE.OP_SUB,OP_TYPE.OP_ADD)
                            }
                            is("b111".U){
                                io.out_op(i).type_ := OP_TYPE.OP_AND
                            }
                            is ("b110".U){
                                io.out_op(i).type_ := OP_TYPE.OP_OR
                            }
                            is ("b100".U){
                                io.out_op(i).type_ := OP_TYPE.OP_XOR
                            }
                            is ("b001".U){
                                io.out_op(i).type_ := OP_TYPE.OP_SLL
                            }
                            is ("b101".U){
                                io.out_op(i).type_ := Mux(opcode_reg(i)(30),OP_TYPE.OP_SRA,OP_TYPE.OP_SRL)
                            }
                            is ("b010".U){
                                io.out_op(i).type_ := OP_TYPE.OP_SLT
                            }
                            is ("b011".U){
                                io.out_op(i).type_ := OP_TYPE.OP_SLTU
                            }
                        }
                    }
                    is("b1".U){//M extension
                        switch(opcode_reg(i)(14,12)){
                            is ("b000".U){//low 32 bits
                                io.out_op(i).type_ := OP_TYPE.OP_MUL
                            }
                            is ("b001".U){//signed*signed, high 32 bits
                                io.out_op(i).type_ := OP_TYPE.OP_MULH
                            }
                            is ("b010".U){//signed*unsigned, high 32 bits
                                io.out_op(i).type_ := OP_TYPE.OP_MULHSU
                            }
                            is ("b011".U){//unsigned*unsigned, high 32 bits
                                io.out_op(i).type_ := OP_TYPE.OP_MULHU
                            }
                            is ("b100".U){//signed a/b, round to 0
                                io.out_op(i).type_ := OP_TYPE.OP_DIV
                            }
                            is ("b101".U){
                                io.out_op(i).type_ := OP_TYPE.OP_DIVU
                            }
                            is ("b110".U){//same sign with a
                                io.out_op(i).type_ := OP_TYPE.OP_REM
                            }
                            is ("b111".U){
                                io.out_op(i).type_ := OP_TYPE.OP_REMU
                            }
                        }
                    }
                }
            }
            is("b0010011".U){//I(*)
                io.out_op(i).rd:=opcode_reg(i)(11,7)
                io.out_op(i).r1:=opcode_reg(i)(19,15)
                io.out_op(i).r2:=opcode_reg(i)(31,20)
                switch(opcode_reg(i)(14,12)){
                    is ("b000".U){
                        io.out_op(i).type_ := OP_TYPE.OP_ADDI
                    }
                    is ("b111".U){
                        io.out_op(i).type_ := OP_TYPE.OP_ANDI
                    }
                    is ("b110".U){
                        io.out_op(i).type_ := OP_TYPE.OP_ORI
                    }
                    is ("b100".U){
                        io.out_op(i).type_ := OP_TYPE.OP_XORI
                    }
                    is ("b001".U){
                        io.out_op(i).type_ := OP_TYPE.OP_SLLI
                    }
                    is ("b101".U){
                        io.out_op(i).type_ := Mux(opcode_reg(i)(30),OP_TYPE.OP_SRAI,OP_TYPE.OP_SRLI)
                    }
                    is ("b010".U){
                        io.out_op(i).type_ := OP_TYPE.OP_SLTI
                    }
                    is ("b011".U){
                        io.out_op(i).type_ := OP_TYPE.OP_SLTIU
                    }
                }
            }
            is("b0000011".U){//I memory
                io.out_op(i).rd:=opcode_reg(i)(11,7)
                io.out_op(i).r1:=opcode_reg(i)(19,15)
                io.out_op(i).r2:=opcode_reg(i)(31,20)
                switch(opcode_reg(i)(14,12)){
                    is ("b000".U){
                        io.out_op(i).type_ := OP_TYPE.OP_LB
                    }
                    is ("b100".U){
                        io.out_op(i).type_ := OP_TYPE.OP_LBU
                    }
                    is ("b001".U){
                        io.out_op(i).type_ := OP_TYPE.OP_LH
                    }
                    is ("b101".U){
                        io.out_op(i).type_ := OP_TYPE.OP_LHU
                    }
                    is ("b010".U){
                        io.out_op(i).type_ := OP_TYPE.OP_LW
                    }
                }
            }
            is("b0100011".U){//S
                io.out_op(i).rd:=Cat(opcode_reg(i)(31,25),opcode_reg(i)(11,7))
                io.out_op(i).r1:=opcode_reg(i)(19,15)
                io.out_op(i).r2:=opcode_reg(i)(24,20)
                switch(opcode_reg(i)(14,12)){
                    is ("b000".U){
                        io.out_op(i).type_ := OP_TYPE.OP_SB
                    }
                    is ("b001".U){
                        io.out_op(i).type_ := OP_TYPE.OP_SH
                    }
                    is ("b010".U){
                        io.out_op(i).type_ := OP_TYPE.OP_SW
                    }
                }
            }
            is("b1100011".U){//B
                io.out_op(i).rd:=Cat(opcode_reg(i)(31,31),opcode_reg(i)(7,7),opcode_reg(i)(30,25),opcode_reg(i)(11,8),false.B)
                io.out_op(i).r1:=opcode_reg(i)(19,15)
                io.out_op(i).r2:=opcode_reg(i)(24,20)
                switch(opcode_reg(i)(14,12)){
                    is ("b000".U){
                        io.out_op(i).type_ := OP_TYPE.OP_BEQ
                    }
                    is ("b101".U){
                        io.out_op(i).type_ := OP_TYPE.OP_BGE
                    }
                    is ("b111".U){
                        io.out_op(i).type_ := OP_TYPE.OP_BGEU
                    }
                    is ("b100".U){
                        io.out_op(i).type_ := OP_TYPE.OP_BLT
                    }
                    is ("b110".U){
                        io.out_op(i).type_ := OP_TYPE.OP_BLTU
                    }
                    is ("b001".U){
                        io.out_op(i).type_ := OP_TYPE.OP_BNE
                    }
                }
            }
            is("b1101111".U){//JAL
                io.out_op(i).rd:=opcode_reg(i)(11,7)
                io.out_op(i).r1:=Cat(opcode_reg(i)(31,31),opcode_reg(i)(19,12),opcode_reg(i)(20,20),opcode_reg(i)(30,21),false.B)
                io.out_op(i).r2:=0.U(32.W)
                io.out_op(i).type_ := OP_TYPE.OP_JAL
            }
            is ("b1100111".U){//JALR
                io.out_op(i).rd:=opcode_reg(i)(11,7)
                io.out_op(i).r1:=opcode_reg(i)(19,15)
                io.out_op(i).r2:=opcode_reg(i)(31,20)
                io.out_op(i).type_ := OP_TYPE.OP_JALR
            }
            is("b0010111".U){//AUIPC
                io.out_op(i).rd:=opcode_reg(i)(11,7)
                io.out_op(i).r1:=Cat(opcode_reg(i)(31,12),0.U(12.W))
                io.out_op(i).r2:=0.U(32.W)
                io.out_op(i).type_ := OP_TYPE.OP_AUIPC
            }
            is("b0110111".U){//LUI
                io.out_op(i).rd:=opcode_reg(i)(11,7)
                io.out_op(i).r1:=Cat(opcode_reg(i)(31,12),0.U(12.W))
                io.out_op(i).r2:=0.U(32.W)
                io.out_op(i).type_ := OP_TYPE.OP_LUI
            }
        }
    }
}
