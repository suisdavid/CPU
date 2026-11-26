package cpu2026
import java.nio.file.{Files,Paths}
import _root_.circt.stage.ChiselStage
object Generate extends App {
  val generated=Paths.get("verilog/generated")
  Files.createDirectories(generated)
  ChiselStage.emitSystemVerilogFile(new StudentTop,
    args=Array("--target-dir",generated.toString),
    firtoolOpts=Array("--disable-all-randomization","--mlir-disable-threading",
      "--preserve-aggregate=all","--strip-debug-info","--lowering-options=disallowLocalVariables"))
  val rtl=generated.resolve("student_top.sv")
  Files.write(rtl,(new String(Files.readAllBytes(rtl),"UTF-8").trim+"\n").getBytes("UTF-8"))
  Files.write(Paths.get("verilog/filelist.f"),"generated/student_top.sv\n".getBytes("UTF-8"))
  println("Generated verilog/generated/student_top.sv")
}
