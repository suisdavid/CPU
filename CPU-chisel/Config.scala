package Config
import chisel3.util.log2Ceil

object CPUConfig {
  private def param(name: String, default: Int): Int =
    sys.props.get("cpu." + name).map(_.toInt).getOrElse(default)
  val issueWidth = param("issueWidth", 4)
  val ROBWidth = param("robDepth", 16)
  val RSWidth = param("rsDepth", 16)
  val ALU_NUM = param("aluCount", 4)
  val setSIZE = param("cacheSets", 128)
  val lineNum = param("cacheWays", 4)
  val PBIT = 8
  require(issueWidth >= 1 && issueWidth <= 8 && (issueWidth & (issueWidth - 1)) == 0)
  require(ROBWidth >= issueWidth && ROBWidth >= 2 && (ROBWidth & (ROBWidth - 1)) == 0)
  require(RSWidth >= issueWidth && ALU_NUM >= 1 && ALU_NUM <= RSWidth)
  require(setSIZE >= 2 && (setSIZE & (setSIZE - 1)) == 0 && lineNum >= 1)
  val robBits = log2Ceil(ROBWidth)
  val setBIT = log2Ceil(setSIZE)
  val bankBits = log2Ceil(issueWidth)
  val cdbCount = ALU_NUM + 3 // integer lanes, shared multiplier, divider, memory
}
