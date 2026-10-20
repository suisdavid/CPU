FIRRTL version 1.2.0
circuit RegNextModule :
  module RegNextModule : @[Users/sam/Desktop/CPU/chisel-hello/helloworld.scala 13:7]
    input clock : Clock @[Users/sam/Desktop/CPU/chisel-hello/helloworld.scala 13:7]
    input reset : UInt<1> @[Users/sam/Desktop/CPU/chisel-hello/helloworld.scala 13:7]
    input io_in : UInt<12> @[Users/sam/Desktop/CPU/chisel-hello/helloworld.scala 14:14]
    output io_out : UInt<12> @[Users/sam/Desktop/CPU/chisel-hello/helloworld.scala 14:14]

    node _io_out_T = add(io_in, UInt<1>("h1")) @[Users/sam/Desktop/CPU/chisel-hello/helloworld.scala 19:27]
    node _io_out_T_1 = tail(_io_out_T, 1) @[Users/sam/Desktop/CPU/chisel-hello/helloworld.scala 19:27]
    reg io_out_REG : UInt<12>, clock with :
      reset => (UInt<1>("h0"), io_out_REG) @[Users/sam/Desktop/CPU/chisel-hello/helloworld.scala 19:20]
    io_out <= io_out_REG @[Users/sam/Desktop/CPU/chisel-hello/helloworld.scala 19:10]
    io_out_REG <= _io_out_T_1 @[Users/sam/Desktop/CPU/chisel-hello/helloworld.scala 19:20]
