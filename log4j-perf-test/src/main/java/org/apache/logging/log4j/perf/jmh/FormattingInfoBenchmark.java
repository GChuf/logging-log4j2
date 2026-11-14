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
package org.apache.logging.log4j.perf.jmh;

import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.core.pattern.FormattingInfo;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures left-aligned padding as used by {@code %-5level}, the pattern most layouts use for the level.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class FormattingInfoBenchmark {

    /** The value being padded; "INFO" needs 1 space for {@code %-5level}, "X" needs 4, "ERROR" none. */
    @Param({"INFO", "X", "ERROR"})
    String value;

    private final FormattingInfo leftAligned = new FormattingInfo(true, 5, Integer.MAX_VALUE, true);

    private final StringBuilder buffer = new StringBuilder(256).append("2026-11-14 13:45:37.965 ");

    @Benchmark
    public int leftAlignedPadding() {
        final int fieldStart = 24;
        buffer.setLength(fieldStart);
        buffer.append(value);
        leftAligned.format(fieldStart, buffer);
        return buffer.length();
    }
}
