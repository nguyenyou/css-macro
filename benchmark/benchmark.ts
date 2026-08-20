import { cpus, platform, release, tmpdir } from "node:os";
import { dirname, join, resolve } from "node:path";
import { mkdir, mkdtemp, rm } from "node:fs/promises";

type Suite = "all" | "compile" | "runtime";
type BenchmarkName = "compileControl" | "compileMacro" | "runtimeFlatten";

interface Options {
  suite: Suite;
  compileRules: number;
  compileSamples: number;
  runtimeRules: number;
  runtimeIterations: number;
  runtimeSamples: number;
  runtimeWarmups: number;
  baseline?: string;
  output?: string;
}

interface Sample {
  timeMs: number;
  peakRssMb: number;
  maxHeapMb?: number;
}

interface Summary {
  samples: Sample[];
  timeMs: {
    min: number;
    median: number;
    p95: number;
  };
  peakRssMb: {
    median: number;
    max: number;
  };
  maxHeapMb?: {
    median: number;
    max: number;
  };
}

interface BenchmarkResult {
  schemaVersion: 1;
  createdAt: string;
  commit: string;
  dirty: boolean;
  machine: {
    platform: string;
    release: string;
    architecture: string;
    cpu: string;
    logicalCpus: number;
    bun: string;
    scalaCli: string;
    java: string;
  };
  options: Omit<Options, "baseline" | "output">;
  benchmarks: Partial<Record<BenchmarkName, Summary>>;
}

interface MeasuredProcess {
  stdout: string;
  wallTimeMs: number;
  peakRssMb: number;
}

const repoRoot = resolve(import.meta.dir, "..");
const scalaSource = join(repoRoot, "src", "css.scala");
const runtimeSource = join(repoRoot, "benchmark", "RuntimeBenchmark.scala");
const megabyte = 1024 * 1024;

const options = parseOptions(process.argv.slice(2));
const temporaryDirectory = await mkdtemp(
  join(tmpdir(), "css-macro-benchmark-")
);

try {
  const benchmarks: Partial<Record<BenchmarkName, Summary>> = {};

  console.log("Preparing benchmark dependencies outside measured samples...");
  if (options.suite === "all" || options.suite === "runtime") {
    benchmarks.runtimeFlatten = summarize(
      await benchmarkRuntime(options, temporaryDirectory)
    );
  }
  if (options.suite === "all" || options.suite === "compile") {
    const compileResults = await benchmarkCompile(options, temporaryDirectory);
    benchmarks.compileControl = summarize(compileResults.control);
    benchmarks.compileMacro = summarize(compileResults.macro);
  }

  const result: BenchmarkResult = {
    schemaVersion: 1,
    createdAt: new Date().toISOString(),
    commit: commandOutput(["git", "rev-parse", "HEAD"]),
    dirty: commandOutput(["git", "status", "--porcelain"]).length > 0,
    machine: machineInformation(),
    options: {
      suite: options.suite,
      compileRules: options.compileRules,
      compileSamples: options.compileSamples,
      runtimeRules: options.runtimeRules,
      runtimeIterations: options.runtimeIterations,
      runtimeSamples: options.runtimeSamples,
      runtimeWarmups: options.runtimeWarmups,
    },
    benchmarks,
  };

  const baseline = options.baseline
    ? await readBaseline(options.baseline, result)
    : undefined;
  printResults(result, baseline);

  if (options.output) {
    const outputPath = resolve(repoRoot, options.output);
    await mkdir(dirname(outputPath), { recursive: true });
    await Bun.write(outputPath, `${JSON.stringify(result, null, 2)}\n`);
    console.log(`\nSaved raw results to ${outputPath}`);
  }
} finally {
  await rm(temporaryDirectory, { recursive: true, force: true });
}

function parseOptions(args: string[]): Options {
  const parsed: Options = {
    suite: "all",
    compileRules: 2000,
    compileSamples: 3,
    runtimeRules: 1000,
    runtimeIterations: 50,
    runtimeSamples: 5,
    runtimeWarmups: 10,
  };

  for (let index = 0; index < args.length; index += 1) {
    const argument = args[index];
    if (argument === "--help") {
      printHelp();
      process.exit(0);
    } else if (argument === "--quick") {
      parsed.compileRules = 100;
      parsed.compileSamples = 1;
      parsed.runtimeRules = 100;
      parsed.runtimeIterations = 1;
      parsed.runtimeSamples = 1;
      parsed.runtimeWarmups = 1;
    } else if (argument === "--suite") {
      const value = requiredValue(args, ++index, argument);
      if (value !== "all" && value !== "compile" && value !== "runtime") {
        throw new Error("--suite must be all, compile, or runtime");
      }
      parsed.suite = value;
    } else if (argument === "--compile-rules") {
      parsed.compileRules = positiveInteger(args, ++index, argument);
    } else if (argument === "--compile-samples") {
      parsed.compileSamples = positiveInteger(args, ++index, argument);
    } else if (argument === "--runtime-rules") {
      parsed.runtimeRules = positiveInteger(args, ++index, argument);
    } else if (argument === "--runtime-iterations") {
      parsed.runtimeIterations = positiveInteger(args, ++index, argument);
    } else if (argument === "--runtime-samples") {
      parsed.runtimeSamples = positiveInteger(args, ++index, argument);
    } else if (argument === "--runtime-warmups") {
      parsed.runtimeWarmups = positiveInteger(args, ++index, argument);
    } else if (argument === "--baseline") {
      parsed.baseline = requiredValue(args, ++index, argument);
    } else if (argument === "--output") {
      parsed.output = requiredValue(args, ++index, argument);
    } else {
      throw new Error(`Unknown option: ${argument}`);
    }
  }

  return parsed;
}

function requiredValue(args: string[], index: number, option: string): string {
  const value = args[index];
  if (!value) {
    throw new Error(`${option} requires a value`);
  }
  return value;
}

function positiveInteger(args: string[], index: number, option: string): number {
  const text = requiredValue(args, index, option);
  const value = Number(text);
  if (!Number.isSafeInteger(value) || value <= 0) {
    throw new Error(`${option} must be a positive integer`);
  }
  return value;
}

function printHelp(): void {
  console.log(`Usage: bun benchmark/benchmark.ts [options]

Measures macro compilation and runtime flattening time plus peak memory.

Options:
  --quick                    One-sample smoke workload
  --suite all|compile|runtime
  --compile-rules N          Static class rules per compile workload (default 2000)
  --compile-samples N        Fresh compile samples (default 3)
  --runtime-rules N          Nested rules per runtime operation (default 1000)
  --runtime-iterations N     Operations per runtime process (default 50)
  --runtime-samples N        Fresh runtime processes (default 5)
  --runtime-warmups N        Warmups per runtime process (default 10)
  --output PATH              Save raw JSON results
  --baseline PATH            Compare with a compatible saved result
  --help                     Show this help`);
}

async function benchmarkRuntime(
  benchmarkOptions: Options,
  workspace: string
): Promise<Sample[]> {
  const bundle = join(workspace, "runtime-benchmark.js");
  await runChecked([
    "scala-cli",
    "--power",
    "package",
    scalaSource,
    runtimeSource,
    "--server=false",
    "--platform",
    "scala-js",
    "--scala",
    "3.8.4",
    "--js-mode",
    "release",
    "--js-module-kind",
    "common",
    "--main-class",
    "benchmark.RuntimeBenchmark",
    "--output",
    bundle,
    "--force",
    "--workspace",
    join(workspace, "runtime-package"),
  ]);

  const samples: Sample[] = [];
  for (let sample = 0; sample < benchmarkOptions.runtimeSamples; sample += 1) {
    console.log(
      `Runtime sample ${sample + 1}/${benchmarkOptions.runtimeSamples}`
    );
    const measured = await measureCommand(
      ["bun", bundle],
      {
        CSS_BENCHMARK_RULES: String(benchmarkOptions.runtimeRules),
        CSS_BENCHMARK_ITERATIONS: String(benchmarkOptions.runtimeIterations),
        CSS_BENCHMARK_WARMUPS: String(benchmarkOptions.runtimeWarmups),
      }
    );
    const payload = parseRuntimeOutput(measured.stdout);
    samples.push({
      timeMs: payload.nanosecondsPerOperation / 1_000_000,
      peakRssMb: Math.max(
        measured.peakRssMb,
        payload.peakRssBytes / megabyte
      ),
      maxHeapMb: payload.maxHeapUsedBytes / megabyte,
    });
  }
  return samples;
}

async function benchmarkCompile(
  benchmarkOptions: Options,
  workspace: string
): Promise<{ control: Sample[]; macro: Sample[] }> {
  const css = buildCompileCss(benchmarkOptions.compileRules);
  const controlSource = join(workspace, "ControlWorkload.scala");
  const macroSource = join(workspace, "MacroWorkload.scala");
  const warmupSource = join(workspace, "WarmupWorkload.scala");
  await Bun.write(
    controlSource,
    buildControlSource(css, benchmarkOptions.compileRules)
  );
  await Bun.write(
    macroSource,
    buildMacroSource(css, benchmarkOptions.compileRules, "MacroWorkload")
  );
  await Bun.write(
    warmupSource,
    buildMacroSource(buildCompileCss(10), 10, "WarmupWorkload")
  );

  await compileSource(warmupSource, join(workspace, "compile-warmup"));

  const control: Sample[] = [];
  const macro: Sample[] = [];
  for (let sample = 0; sample < benchmarkOptions.compileSamples; sample += 1) {
    const order = sample % 2 === 0
      ? (["control", "macro"] as const)
      : (["macro", "control"] as const);

    for (const kind of order) {
      console.log(
        `Compile ${kind} sample ${sample + 1}/${benchmarkOptions.compileSamples}`
      );
      const source = kind === "control" ? controlSource : macroSource;
      const measured = await compileSource(
        source,
        join(workspace, `compile-${kind}-${sample}`)
      );
      const result = {
        timeMs: measured.wallTimeMs,
        peakRssMb: measured.peakRssMb,
      };
      if (kind === "control") {
        control.push(result);
      } else {
        macro.push(result);
      }
    }
  }
  return { control, macro };
}

async function compileSource(
  workloadSource: string,
  workspace: string
): Promise<MeasuredProcess> {
  return measureCommand([
    "scala-cli",
    "compile",
    scalaSource,
    workloadSource,
    "--server=false",
    "--platform",
    "scala-js",
    "--scala",
    "3.8.4",
    "--scalac-option",
    "-Werror",
    "--workspace",
    workspace,
  ]);
}

function buildCompileCss(ruleCount: number): string {
  const rules: string[] = [];
  for (let index = 0; index < ruleCount; index += 1) {
    rules.push(
      `.class${index} { color: rgb(${index % 255}, 20, 30); padding: ${index % 32}px; }`
    );
  }
  return rules.join("\n");
}

function buildMacroSource(
  css: string,
  ruleCount: number,
  objectName: string
): string {
  return `package benchmark

import www.CssMacro.css

object ${objectName} {
  val styles = css"""${css}"""
  val first: String = styles.classNames.class0
  val last: String = styles.classNames.class${ruleCount - 1}
}
`;
}

function buildControlSource(css: string, ruleCount: number): string {
  const chunks = splitString(css, 12000)
    .map((chunk) => `    """${chunk}"""`)
    .join(",\n");
  return `package benchmark

object ControlWorkload {
  val cssText: String = List(
${chunks}
  ).mkString
  val first: String = "class0"
  val last: String = "class${ruleCount - 1}"
}
`;
}

function splitString(value: string, chunkLength: number): string[] {
  const chunks: string[] = [];
  for (let index = 0; index < value.length; index += chunkLength) {
    chunks.push(value.slice(index, index + chunkLength));
  }
  return chunks;
}

async function measureCommand(
  command: string[],
  environment: Record<string, string> = {}
): Promise<MeasuredProcess> {
  const startedAt = performance.now();
  const subprocess = Bun.spawn(command, {
    cwd: repoRoot,
    stdout: "pipe",
    stderr: "pipe",
    env: { ...process.env, ...environment },
  });
  const stdoutPromise = new Response(subprocess.stdout).text();
  const stderrPromise = new Response(subprocess.stderr).text();
  const [exitCode, stdout, stderr] = await Promise.all([
    subprocess.exited,
    stdoutPromise,
    stderrPromise,
  ]);
  if (exitCode !== 0) {
    throw new Error(
      `Command failed (${exitCode}): ${command.join(" ")}\n${stdout}${stderr}`
    );
  }
  const resourceUsage = subprocess.resourceUsage();
  if (!resourceUsage) {
    throw new Error(`Resource usage unavailable: ${command.join(" ")}`);
  }

  return {
    stdout,
    wallTimeMs: performance.now() - startedAt,
    peakRssMb: resourceUsage.maxRSS / megabyte,
  };
}

async function runChecked(command: string[]): Promise<void> {
  await measureCommand(command);
}

function parseRuntimeOutput(stdout: string): {
  nanosecondsPerOperation: number;
  peakRssBytes: number;
  maxHeapUsedBytes: number;
} {
  const line = stdout
    .trim()
    .split("\n")
    .findLast((candidate) => candidate.startsWith("{"));
  if (!line) {
    throw new Error(`Runtime benchmark produced no JSON result:\n${stdout}`);
  }
  return JSON.parse(line);
}

function summarize(samples: Sample[]): Summary {
  const times = samples.map((sample) => sample.timeMs);
  const rss = samples.map((sample) => sample.peakRssMb);
  const heap = samples.flatMap((sample) =>
    sample.maxHeapMb === undefined ? [] : [sample.maxHeapMb]
  );
  return {
    samples,
    timeMs: {
      min: Math.min(...times),
      median: median(times),
      p95: percentile(times, 0.95),
    },
    peakRssMb: {
      median: median(rss),
      max: Math.max(...rss),
    },
    maxHeapMb: heap.length
      ? { median: median(heap), max: Math.max(...heap) }
      : undefined,
  };
}

function median(values: number[]): number {
  const sorted = values.toSorted((left, right) => left - right);
  const middle = Math.floor(sorted.length / 2);
  return sorted.length % 2 === 0
    ? (sorted[middle - 1] + sorted[middle]) / 2
    : sorted[middle];
}

function percentile(values: number[], quantile: number): number {
  const sorted = values.toSorted((left, right) => left - right);
  const index = Math.max(0, Math.ceil(sorted.length * quantile) - 1);
  return sorted[index];
}

async function readBaseline(
  path: string,
  current: BenchmarkResult
): Promise<BenchmarkResult> {
  const baseline: BenchmarkResult = JSON.parse(
    await Bun.file(resolve(repoRoot, path)).text()
  );
  if (baseline.schemaVersion !== 1) {
    throw new Error(`Unsupported baseline schema: ${baseline.schemaVersion}`);
  }
  const comparableOptions: Array<
    keyof Omit<Options, "baseline" | "output" | "suite">
  > = [];
  if (baseline.benchmarks.compileMacro && current.benchmarks.compileMacro) {
    comparableOptions.push("compileRules", "compileSamples");
  }
  if (baseline.benchmarks.runtimeFlatten && current.benchmarks.runtimeFlatten) {
    comparableOptions.push(
      "runtimeRules",
      "runtimeIterations",
      "runtimeSamples",
      "runtimeWarmups"
    );
  }
  for (const option of comparableOptions) {
    if (baseline.options[option] !== current.options[option]) {
      throw new Error(
        `Baseline mismatch: ${option} is ${baseline.options[option]}, current run is ${current.options[option]}`
      );
    }
  }
  const comparableMachineFields = [
    "platform",
    "architecture",
    "cpu",
    "logicalCpus",
    "bun",
    "scalaCli",
    "java",
  ] as const;
  for (const field of comparableMachineFields) {
    if (baseline.machine[field] !== current.machine[field]) {
      throw new Error(
        `Baseline mismatch: ${field} is ${baseline.machine[field]}, current run is ${current.machine[field]}`
      );
    }
  }
  return baseline;
}

function printResults(
  result: BenchmarkResult,
  baseline?: BenchmarkResult
): void {
  console.log("\nBenchmark results");
  console.log(
    "benchmark         median time   p95 time   median RSS   max RSS    max heap   time delta   RSS delta"
  );
  console.log("-".repeat(106));

  const labels: Record<BenchmarkName, string> = {
    compileControl: "compile-control",
    compileMacro: "compile-macro",
    runtimeFlatten: "runtime-flatten",
  };
  for (const name of Object.keys(labels) as BenchmarkName[]) {
    const summary = result.benchmarks[name];
    if (!summary) {
      continue;
    }
    const previous = baseline?.benchmarks[name];
    const values = [
      labels[name].padEnd(17),
      formatMilliseconds(summary.timeMs.median).padStart(11),
      formatMilliseconds(summary.timeMs.p95).padStart(10),
      formatMegabytes(summary.peakRssMb.median).padStart(12),
      formatMegabytes(summary.peakRssMb.max).padStart(9),
      (summary.maxHeapMb
        ? formatMegabytes(summary.maxHeapMb.max)
        : "-").padStart(11),
      formatDelta(
        summary.timeMs.median,
        previous?.timeMs.median
      ).padStart(12),
      formatDelta(
        summary.peakRssMb.median,
        previous?.peakRssMb.median
      ).padStart(11),
    ];
    console.log(values.join(" "));
  }

  const control = result.benchmarks.compileControl;
  const macro = result.benchmarks.compileMacro;
  if (control && macro) {
    console.log(
      `\nMacro/control ratio: ${(macro.timeMs.median / control.timeMs.median).toFixed(2)}x time, ${(macro.peakRssMb.median / control.peakRssMb.median).toFixed(2)}x peak RSS`
    );
  }
  console.log(
    `Commit ${result.commit.slice(0, 12)}${result.dirty ? " (dirty)" : ""}; ${result.machine.cpu}; ${result.machine.logicalCpus} logical CPUs`
  );
  console.log(
    `Workload: compile ${result.options.compileRules} rules x ${result.options.compileSamples} samples; runtime ${result.options.runtimeRules} rules x ${result.options.runtimeIterations} iterations x ${result.options.runtimeSamples} processes (${result.options.runtimeWarmups} warmups each)`
  );
}

function formatMilliseconds(value: number): string {
  return value >= 1000 ? `${(value / 1000).toFixed(2)} s` : `${value.toFixed(2)} ms`;
}

function formatMegabytes(value: number): string {
  return `${value.toFixed(1)} MB`;
}

function formatDelta(current: number, baseline?: number): string {
  if (baseline === undefined) {
    return "-";
  }
  const percentage = ((current - baseline) / baseline) * 100;
  return `${percentage >= 0 ? "+" : ""}${percentage.toFixed(1)}%`;
}

function machineInformation(): BenchmarkResult["machine"] {
  const cpu = cpus()[0]?.model ?? "unknown CPU";
  return {
    platform: platform(),
    release: release(),
    architecture: process.arch,
    cpu,
    logicalCpus: cpus().length,
    bun: Bun.version,
    scalaCli: commandOutput(["scala-cli", "version"]),
    java: commandOutput(["java", "-version"]).split("\n")[0],
  };
}

function commandOutput(command: string[]): string {
  const result = Bun.spawnSync(command, {
    cwd: repoRoot,
    stdout: "pipe",
    stderr: "pipe",
  });
  if (result.exitCode !== 0) {
    throw new Error(`Command failed: ${command.join(" ")}`);
  }
  const decoder = new TextDecoder();
  return `${decoder.decode(result.stdout)}${decoder.decode(result.stderr)}`.trim();
}
