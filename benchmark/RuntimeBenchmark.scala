package benchmark

import scala.scalajs.js
import www.CssFlattener

object RuntimeBenchmark {
  private final case class MemoryUsage(rssBytes: Double, heapUsedBytes: Double)

  def main(args: Array[String]): Unit = {
    val ruleCount =
      positiveEnvironment("CSS_BENCHMARK_RULES", 1000, "rule count")
    val iterations =
      positiveEnvironment("CSS_BENCHMARK_ITERATIONS", 50, "iteration count")
    val warmups =
      positiveEnvironment("CSS_BENCHMARK_WARMUPS", 10, "warmup count")
    val css = buildCss(ruleCount)

    var checksum = 0L
    var warmup = 0
    while (warmup < warmups) {
      checksum += CssFlattener.flatten(css).length
      warmup += 1
    }

    var peakRssBytes = 0.0
    var maxHeapUsedBytes = 0.0
    var elapsedNanoseconds = 0L
    var iteration = 0
    while (iteration < iterations) {
      val operationStartedAt = System.nanoTime()
      val flattened = CssFlattener.flatten(css)
      elapsedNanoseconds += System.nanoTime() - operationStartedAt
      checksum += flattened.length
      val memoryUsage = currentMemoryUsage()
      peakRssBytes = math.max(peakRssBytes, memoryUsage.rssBytes)
      maxHeapUsedBytes = math.max(maxHeapUsedBytes, memoryUsage.heapUsedBytes)
      iteration += 1
    }
    val nanosecondsPerOperation =
      elapsedNanoseconds.toDouble / iterations.toDouble

    println(
      s"""{"nanosecondsPerOperation":$nanosecondsPerOperation,"peakRssBytes":$peakRssBytes,"maxHeapUsedBytes":$maxHeapUsedBytes,"checksum":$checksum}"""
    )
  }

  private def positiveEnvironment(
      name: String,
      default: Int,
      label: String
  ): Int = {
    val rawValue = js.Dynamic.global.process.env.selectDynamic(name)
    val value = if (js.isUndefined(rawValue)) {
      default
    } else {
      rawValue.asInstanceOf[String].toIntOption.getOrElse(default)
    }
    require(value > 0, s"$label must be positive")
    value
  }

  private def buildCss(ruleCount: Int): String = {
    val builder = StringBuilder(ruleCount * 180)
    var index = 0
    while (index < ruleCount) {
      builder
        .append(".component-")
        .append(index)
        .append(", .alias-")
        .append(index)
        .append(" { color: rgb(")
        .append(index % 255)
        .append(", 20, 30); &:hover, &.active { color: blue; }")
        .append(" @media (min-width: 40rem) { .child-")
        .append(index)
        .append(" { display: grid; } } }")
      index += 1
    }
    builder.toString
  }

  private def currentMemoryUsage(): MemoryUsage = {
    val usage = js.Dynamic.global.process.memoryUsage()
    MemoryUsage(
      rssBytes = usage.rss.asInstanceOf[Double],
      heapUsedBytes = usage.heapUsed.asInstanceOf[Double]
    )
  }
}
