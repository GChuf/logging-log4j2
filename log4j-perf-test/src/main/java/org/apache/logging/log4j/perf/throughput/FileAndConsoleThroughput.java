/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.logging.log4j.perf.throughput;

import java.io.BufferedReader;
import java.io.File;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintStream;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.ConsoleAppender;
import org.apache.logging.log4j.core.config.builder.api.AppenderComponentBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilder;
import org.apache.logging.log4j.core.config.builder.api.ConfigurationBuilderFactory;
import org.apache.logging.log4j.core.config.builder.impl.BuiltConfiguration;

/**
 * Measures how many lines and how many bytes synchronous Log4j writes in a fixed time span (one minute by default),
 * once to a file ({@code FileAppender}) and once to the console ({@code ConsoleAppender}).
 * <p>
 * For each target the following call styles are measured:
 * </p>
 * <ul>
 *     <li>{@code plain}: {@code logger.info("literal")}</li>
 *     <li>{@code param-string}: {@code logger.info("literal {}", stringVar)}</li>
 *     <li>{@code param-int}: {@code logger.info("literal {}", intVar)}</li>
 *     <li>{@code concat}: {@code logger.info("literal " + stringVar)}</li>
 * </ul>
 * <p>
 * This is deliberately <em>not</em> a JMH benchmark: JMH forks redirect {@code System.out} of the benchmark JVM to the
 * JMH host process, which would make the console measurement meaningless. Instead, each (target, scenario) pair runs
 * in a fresh JVM (same JVM options and class path as the launcher) with its standard output inherited from the launcher,
 * so the console appender writes where the launcher's stdout goes (terminal, file, {@code /dev/null}, {@code NUL}...).
 * All reporting goes to <strong>stderr</strong>, so stdout can be redirected without losing the results.
 * </p>
 * <h2>Usage</h2>
 * <pre>
 * java -cp log4j-perf-test/target/log4j-perf-test-*-uber.jar \
 *   org.apache.logging.log4j.perf.throughput.FileAndConsoleThroughput [options]
 *
 * Options:
 *   --duration &lt;seconds&gt;        measurement time per scenario (default: 60)
 *   --warmup &lt;seconds&gt;          warm-up time per scenario, not measured (default: 15)
 *   --targets &lt;list&gt;            comma separated: file,console (default: both)
 *   --scenarios &lt;list&gt;          comma separated: plain,param-string,param-int,concat (default: all)
 *   --dir &lt;directory&gt;           directory for the log files (default: target/throughput)
 *   --immediate-flush &lt;bool&gt;    appender immediateFlush attribute (default: true, the Log4j default)
 *   --pattern &lt;pattern&gt;         PatternLayout pattern (default: see DEFAULT_PATTERN)
 *   --keep-files                 do not delete the log files after the run
 *   --no-fork                    run all scenarios in this JVM (less accurate, convenient in an IDE)
 * </pre>
 */
public final class FileAndConsoleThroughput {

    static final String DEFAULT_PATTERN = "%d{yyyy-MM-dd HH:mm:ss.SSS} %-5level [%t] %c{1.} - %m%n";

    private static final String LOGGER_NAME = "com.example.app.OrderService";

    private static final String RESULT_MARKER = "##THROUGHPUT-RESULT##";

    /** Number of log calls between two deadline checks, so {@link System#nanoTime()} does not skew the result. */
    private static final int BATCH = 64;

    /** Pre-computed string arguments, so creating the argument is not part of the measurement. */
    private static final String[] VALUES = new String[1024];

    private static final int VALUES_MASK = VALUES.length - 1;

    static {
        for (int i = 0; i < VALUES.length; i++) {
            VALUES[i] = String.format(Locale.ROOT, "customer-%04d", i);
        }
    }

    enum Target {
        FILE,
        CONSOLE
    }

    /**
     * One enum constant per call style; each has its own copy of the loop so that every logging call site stays
     * monomorphic and is compiled exactly like it would be in application code.
     */
    enum Scenario {
        PLAIN("plain", "logger.info(\"Processing order\")") {
            @Override
            long logUntil(final Logger logger, final long deadline) {
                long count = 0;
                do {
                    for (int j = 0; j < BATCH; j++) {
                        logger.info("Processing order");
                    }
                    count += BATCH;
                } while (System.nanoTime() - deadline < 0);
                return count;
            }
        },

        PARAM_STRING("param-string", "logger.info(\"Processing order {}\", stringVar)") {
            @Override
            long logUntil(final Logger logger, final long deadline) {
                long count = 0;
                int i = 0;
                do {
                    for (int j = 0; j < BATCH; j++) {
                        final String value = VALUES[i++ & VALUES_MASK];
                        logger.info("Processing order {}", value);
                    }
                    count += BATCH;
                } while (System.nanoTime() - deadline < 0);
                return count;
            }
        },

        PARAM_INT("param-int", "logger.info(\"Processing order {}\", intVar)") {
            @Override
            long logUntil(final Logger logger, final long deadline) {
                long count = 0;
                int i = 0;
                do {
                    for (int j = 0; j < BATCH; j++) {
                        // Autoboxed, exactly as in typical application code.
                        logger.info("Processing order {}", i++);
                    }
                    count += BATCH;
                } while (System.nanoTime() - deadline < 0);
                return count;
            }
        },

        CONCAT("concat", "logger.info(\"Processing order \" + stringVar)") {
            @Override
            long logUntil(final Logger logger, final long deadline) {
                long count = 0;
                int i = 0;
                do {
                    for (int j = 0; j < BATCH; j++) {
                        final String value = VALUES[i++ & VALUES_MASK];
                        logger.info("Processing order " + value);
                    }
                    count += BATCH;
                } while (System.nanoTime() - deadline < 0);
                return count;
            }
        };

        final String id;
        final String description;

        Scenario(final String id, final String description) {
            this.id = id;
            this.description = description;
        }

        /** Logs until {@code deadline} (a {@link System#nanoTime()} value) and returns the number of lines logged. */
        abstract long logUntil(Logger logger, long deadline);

        static Scenario fromId(final String id) {
            for (final Scenario scenario : values()) {
                if (scenario.id.equalsIgnoreCase(id) || scenario.name().equalsIgnoreCase(id)) {
                    return scenario;
                }
            }
            throw new IllegalArgumentException(
                    "Unknown scenario '" + id + "', expected one of plain, param-string, param-int, concat");
        }
    }

    static final class Options {
        int durationSeconds = 60;
        int warmupSeconds = 15;
        List<Target> targets = Arrays.asList(Target.values());
        List<Scenario> scenarios = Arrays.asList(Scenario.values());
        File dir = new File("target/throughput");
        boolean immediateFlush = true;
        String pattern = DEFAULT_PATTERN;
        boolean keepFiles;
        boolean fork = true;

        /** Set only in a forked child JVM. */
        Target childTarget;

        Scenario childScenario;

        static Options parse(final String[] args) {
            final Options options = new Options();
            for (int i = 0; i < args.length; i++) {
                final String arg = args[i];
                switch (arg) {
                    case "--duration":
                        options.durationSeconds = Integer.parseInt(args[++i]);
                        break;
                    case "--warmup":
                        options.warmupSeconds = Integer.parseInt(args[++i]);
                        break;
                    case "--targets":
                        options.targets = new ArrayList<>();
                        for (final String target : args[++i].split(",")) {
                            options.targets.add(Target.valueOf(target.trim().toUpperCase(Locale.ROOT)));
                        }
                        break;
                    case "--scenarios":
                        options.scenarios = new ArrayList<>();
                        for (final String scenario : args[++i].split(",")) {
                            options.scenarios.add(Scenario.fromId(scenario.trim()));
                        }
                        break;
                    case "--dir":
                        options.dir = new File(args[++i]);
                        break;
                    case "--immediate-flush":
                        options.immediateFlush = Boolean.parseBoolean(args[++i]);
                        break;
                    case "--pattern":
                        options.pattern = args[++i];
                        break;
                    case "--keep-files":
                        options.keepFiles = true;
                        break;
                    case "--no-fork":
                        options.fork = false;
                        break;
                    case "--child":
                        options.childTarget = Target.valueOf(args[++i]);
                        options.childScenario = Scenario.valueOf(args[++i]);
                        break;
                    default:
                        throw new IllegalArgumentException("Unknown option: " + arg);
                }
            }
            return options;
        }

        /** The options a forked child needs, in command line form. */
        List<String> toChildArgs(final Target target, final Scenario scenario) {
            final List<String> args = new ArrayList<>();
            args.add("--duration");
            args.add(Integer.toString(durationSeconds));
            args.add("--warmup");
            args.add(Integer.toString(warmupSeconds));
            args.add("--dir");
            args.add(dir.getPath());
            args.add("--immediate-flush");
            args.add(Boolean.toString(immediateFlush));
            args.add("--pattern");
            args.add(pattern);
            if (keepFiles) {
                args.add("--keep-files");
            }
            args.add("--child");
            args.add(target.name());
            args.add(scenario.name());
            return args;
        }
    }

    static final class Result {
        final Target target;
        final Scenario scenario;
        final long lines;
        final long bytes;
        final long nanos;

        Result(final Target target, final Scenario scenario, final long lines, final long bytes, final long nanos) {
            this.target = target;
            this.scenario = scenario;
            this.lines = lines;
            this.bytes = bytes;
            this.nanos = nanos;
        }

        String serialize() {
            return String.join(
                    "\t",
                    RESULT_MARKER,
                    target.name(),
                    scenario.name(),
                    Long.toString(lines),
                    Long.toString(bytes),
                    Long.toString(nanos));
        }

        static Result deserialize(final String line) {
            final String[] parts = line.split("\t");
            return new Result(
                    Target.valueOf(parts[1]),
                    Scenario.valueOf(parts[2]),
                    Long.parseLong(parts[3]),
                    Long.parseLong(parts[4]),
                    Long.parseLong(parts[5]));
        }
    }

    /** Counts the bytes the console appender hands to {@code System.out}. */
    private static final class CountingOutputStream extends FilterOutputStream {
        private long count;

        CountingOutputStream(final OutputStream out) {
            super(out);
        }

        @Override
        public void write(final int b) throws IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(final byte[] b, final int off, final int len) throws IOException {
            out.write(b, off, len);
            count += len;
        }

        long count() {
            return count;
        }
    }

    private FileAndConsoleThroughput() {}

    public static void main(final String[] args) throws Exception {
        final Options options = Options.parse(args);
        if (options.childTarget != null) {
            // Forked child: run a single scenario and report the result to the parent via stderr.
            final Result result = runScenario(options, options.childTarget, options.childScenario);
            System.err.println(result.serialize());
            System.err.flush();
            return;
        }

        final PrintStream report = System.err;
        report.printf(
                Locale.ROOT,
                "Log4j throughput: warm-up %ds + measurement %ds per scenario, immediateFlush=%s, fork=%s%n"
                        + "Java %s (%s), pattern: %s%n",
                options.warmupSeconds,
                options.durationSeconds,
                options.immediateFlush,
                options.fork,
                System.getProperty("java.version"),
                System.getProperty("java.vm.name"),
                options.pattern);
        report.println("Note: all results go to stderr; the console scenarios write to stdout.");

        final List<Result> results = new ArrayList<>();
        for (final Target target : options.targets) {
            for (final Scenario scenario : options.scenarios) {
                report.printf(Locale.ROOT, "Running %-7s %-12s %s ...%n", target, scenario.id, scenario.description);
                final Result result =
                        options.fork ? runForked(options, target, scenario) : runScenario(options, target, scenario);
                if (result != null) {
                    results.add(result);
                    printRow(report, result, false);
                }
            }
        }
        printTable(report, results, options.durationSeconds);
    }

    private static Result runForked(final Options options, final Target target, final Scenario scenario)
            throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>();
        // Same JVM as the launcher; avoids ProcessHandle, so the harness also runs on Java 8.
        command.add(Paths.get(System.getProperty("java.home"), "bin", "java").toString());
        for (final String jvmArg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
            // A debugger agent would fail in the child because its port is already taken.
            if (!jvmArg.startsWith("-agentlib:jdwp")) {
                command.add(jvmArg);
            }
        }
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(FileAndConsoleThroughput.class.getName());
        command.addAll(options.toChildArgs(target, scenario));

        final Process process = new ProcessBuilder(command)
                .redirectInput(ProcessBuilder.Redirect.INHERIT)
                // The console appender of the child writes to wherever our stdout goes.
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .start();

        Result result = null;
        try (BufferedReader reader =
                new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith(RESULT_MARKER)) {
                    result = Result.deserialize(line);
                } else {
                    System.err.println(line);
                }
            }
        }
        final int exitCode = process.waitFor();
        if (exitCode != 0 || result == null) {
            System.err.printf(
                    Locale.ROOT, "  FAILED: child JVM exited with code %d for %s/%s%n", exitCode, target, scenario.id);
            return null;
        }
        return result;
    }

    static Result runScenario(final Options options, final Target target, final Scenario scenario) throws IOException {
        final File logFile = new File(options.dir, "throughput-" + scenario.id + ".log");
        final PrintStream originalOut = System.out;
        CountingOutputStream consoleCounter = null;
        if (target == Target.FILE) {
            if (!options.dir.isDirectory() && !options.dir.mkdirs()) {
                throw new IOException("Cannot create directory " + options.dir);
            }
            if (logFile.exists() && !logFile.delete()) {
                throw new IOException("Cannot delete " + logFile);
            }
        } else {
            // Must be installed before the configuration is built, since ConsoleAppender captures System.out.
            consoleCounter = new CountingOutputStream(originalOut);
            System.setOut(new PrintStream(consoleCounter, false));
        }

        final LoggerContext context = new LoggerContext("throughput-" + target + "-" + scenario.id);
        try {
            context.start(createConfiguration(options, target, logFile));
            final Logger logger = context.getLogger(LOGGER_NAME);

            // Warm-up: lets the JIT compile the logging path; not measured.
            scenario.logUntil(logger, System.nanoTime() + TimeUnit.SECONDS.toNanos(options.warmupSeconds));

            final long bytesBefore = target == Target.FILE ? logFile.length() : consoleCounter.count();
            final long start = System.nanoTime();
            final long lines = scenario.logUntil(logger, start + TimeUnit.SECONDS.toNanos(options.durationSeconds));
            final long nanos = System.nanoTime() - start;

            // Stopping flushes any buffered bytes, so the byte count is complete.
            context.stop();
            final long bytesAfter = target == Target.FILE ? logFile.length() : consoleCounter.count();
            return new Result(target, scenario, lines, bytesAfter - bytesBefore, nanos);
        } finally {
            if (context.isStarted()) {
                context.stop();
            }
            if (consoleCounter != null) {
                System.out.flush();
                System.setOut(originalOut);
            }
            if (target == Target.FILE && !options.keepFiles) {
                // These files get big (often several GiB per minute).
                logFile.delete();
            }
        }
    }

    private static BuiltConfiguration createConfiguration(
            final Options options, final Target target, final File logFile) {
        final ConfigurationBuilder<BuiltConfiguration> builder = ConfigurationBuilderFactory.newConfigurationBuilder();
        builder.setConfigurationName("throughput");
        builder.setStatusLevel(Level.WARN);
        final AppenderComponentBuilder appender;
        if (target == Target.FILE) {
            appender = builder.newAppender("out", "File")
                    .addAttribute("fileName", logFile.getPath())
                    .addAttribute("append", false)
                    .addAttribute("bufferedIo", true);
        } else {
            appender = builder.newAppender("out", "Console").addAttribute("target", ConsoleAppender.Target.SYSTEM_OUT);
        }
        appender.addAttribute("immediateFlush", options.immediateFlush)
                .add(builder.newLayout("PatternLayout").addAttribute("pattern", options.pattern));
        builder.add(appender);
        builder.add(builder.newRootLogger(Level.INFO).add(builder.newAppenderRef("out")));
        return builder.build(false);
    }

    private static void printTable(final PrintStream out, final List<Result> results, final int durationSeconds) {
        out.println();
        out.printf(
                Locale.ROOT,
                "Results (per-minute values are extrapolated from the %ds measurement; MiB = 1024*1024 bytes)%n",
                durationSeconds);
        printRow(out, null, true);
        for (final Result result : results) {
            printRow(out, result, false);
        }
    }

    private static void printRow(final PrintStream out, final Result r, final boolean header) {
        if (header) {
            out.printf(
                    Locale.ROOT,
                    "%-8s %-13s %15s %12s %13s %10s %10s %10s%n",
                    "Target",
                    "Scenario",
                    "Lines/min",
                    "MiB/min",
                    "Lines/s",
                    "MiB/s",
                    "ns/line",
                    "B/line");
            return;
        }
        final double seconds = r.nanos / 1e9;
        final double mib = r.bytes / (1024.0 * 1024.0);
        out.printf(
                Locale.ROOT,
                "%-8s %-13s %,15.0f %,12.1f %,13.0f %,10.1f %,10.1f %,10.1f%n",
                r.target,
                r.scenario.id,
                r.lines / seconds * 60,
                mib / seconds * 60,
                r.lines / seconds,
                mib / seconds,
                r.lines == 0 ? 0.0 : (double) r.nanos / r.lines,
                r.lines == 0 ? 0.0 : (double) r.bytes / r.lines);
    }
}
