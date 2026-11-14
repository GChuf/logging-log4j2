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

import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.message.ReusableParameterizedMessage;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Measures the garbage-free parameterized message path used by synchronous loggers: pattern analysis on
 * {@link ReusableParameterizedMessage#set(String, Object...)} followed by {@link ReusableParameterizedMessage#formatTo}.
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
public class ReusableParameterizedMessageBenchmark {

    @Param({
        "Processing order {}",
        "Processing order {} for customer {} in region {}",
        "A considerably longer message that describes in some detail what the application is doing right now: {}"
    })
    String pattern;

    private final ReusableParameterizedMessage message = new ReusableParameterizedMessage();

    private final StringBuilder buffer = new StringBuilder(256);

    private Object[] args;

    @Setup
    public void setup() {
        args = new Object[ParameterizedMessage.countArgumentPlaceholders(pattern)];
        Arrays.fill(args, "customer-0042");
    }

    @Benchmark
    public int setAndFormat() {
        buffer.setLength(0);
        message.set(pattern, args).formatTo(buffer);
        return buffer.length();
    }
}
