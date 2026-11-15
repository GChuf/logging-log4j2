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
package org.apache.logging.log4j.spi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LoggingException;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.message.Message;
import org.apache.logging.log4j.message.SimpleMessage;
import org.apache.logging.log4j.test.TestLogger;
import org.junit.jupiter.api.Test;

/**
 * Tests {@link AbstractLogger#getRecursionDepth()} during and after logging calls.
 */
class AbstractLoggerRecursionDepthTest {

    private static final String FQCN = AbstractLoggerRecursionDepthTest.class.getName();

    /** Records the recursion depth seen by each event; can log again or fail while logging. */
    private static final class DepthRecordingLogger extends TestLogger {

        private static final long serialVersionUID = 1L;

        private final List<Integer> depths = new ArrayList<>();

        private transient Runnable nestedLogging;

        private transient RuntimeException failure;

        @Override
        protected void log(
                final Level level,
                final Marker marker,
                final String fqcn,
                final StackTraceElement location,
                final Message message,
                final Throwable throwable) {
            depths.add(AbstractLogger.getRecursionDepth());
            final Runnable nested = nestedLogging;
            nestedLogging = null;
            if (nested != null) {
                nested.run();
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    @Test
    void depth_is_one_while_logging_and_zero_afterwards() {
        final DepthRecordingLogger logger = new DepthRecordingLogger();
        logger.info("message {}", "argument");
        assertThat(logger.depths).containsExactly(1);
        assertThat(AbstractLogger.getRecursionDepth()).isZero();
    }

    @Test
    void nested_logging_increases_the_depth() {
        final DepthRecordingLogger logger = new DepthRecordingLogger();
        logger.nestedLogging = () -> logger.info("nested");
        logger.info("outer");
        assertThat(logger.depths).containsExactly(1, 2);
        assertThat(AbstractLogger.getRecursionDepth()).isZero();
    }

    @Test
    void depth_is_restored_after_a_suppressed_exception() {
        final DepthRecordingLogger logger = new DepthRecordingLogger();
        logger.failure = new IllegalStateException("expected");
        logger.info("message");
        assertThat(logger.depths).containsExactly(1);
        assertThat(AbstractLogger.getRecursionDepth()).isZero();
    }

    @Test
    void depth_is_restored_after_a_propagated_exception() {
        final DepthRecordingLogger logger = new DepthRecordingLogger();
        logger.failure = new LoggingException("expected");
        assertThatThrownBy(() -> logger.info("message")).isSameAs(logger.failure);
        assertThat(AbstractLogger.getRecursionDepth()).isZero();
    }

    @Test
    void location_aware_logging_tracks_the_depth() {
        final DepthRecordingLogger logger = new DepthRecordingLogger();
        logger.nestedLogging = () -> logger.logMessage(Level.INFO, null, FQCN, null, new SimpleMessage("nested"), null);
        logger.logMessage(Level.INFO, null, FQCN, null, new SimpleMessage("outer"), null);
        assertThat(logger.depths).containsExactly(1, 2);
        assertThat(AbstractLogger.getRecursionDepth()).isZero();

        logger.failure = new LoggingException("expected");
        assertThatThrownBy(() -> logger.logMessage(Level.INFO, null, FQCN, null, new SimpleMessage("failing"), null))
                .isSameAs(logger.failure);
        assertThat(AbstractLogger.getRecursionDepth()).isZero();
    }
}
