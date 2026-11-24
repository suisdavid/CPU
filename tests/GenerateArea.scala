package cpu2026

import java.nio.file.{Files, Paths}
import _root_.circt.stage.ChiselStage

/** Lower aggregates before emitting RTL for the course Yosys frontend. */
object GenerateArea extends App {
  val generated = Paths.get(args.headOption.getOrElse("build/area-native-input"))
  Files.createDirectories(generated)
  ChiselStage.emitSystemVerilogFile(new StudentTop,
    args = Array("--target-dir", generated.toString),
    firtoolOpts = Array("--disable-all-randomization", "--mlir-disable-threading",
      "--preserve-aggregate=none", "--strip-debug-info",
      "--lowering-options=disallowLocalVariables"))
  Files.write(generated.resolve("filelist.f"), "student_top.sv\n".getBytes("UTF-8"))
  println(s"Generated ${generated.resolve("student_top.sv")}")
}
