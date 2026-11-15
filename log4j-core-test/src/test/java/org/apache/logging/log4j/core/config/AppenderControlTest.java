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
package org.apache.logging.log4j.core.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.ErrorHandler;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.apache.logging.log4j.message.SimpleMessage;
import org.junit.jupiter.api.Test;

/**
 * Tests the recursion guard of {@link AppenderControl}.
 */
class AppenderControlTest {

    private static final LogEvent EVENT = Log4jLogEvent.newBuilder()
            .setLevel(Level.INFO)
            .setMessage(new SimpleMessage("message"))
            .build();

    /** Calls its own {@link AppenderControl} again from {@code append()}, or throws if asked to. */
    private static final class ReentrantAppender extends AbstractAppender {

        private final List<String> errors = new ArrayList<>();

        private int appendCount;

        private AppenderControl control;

        private RuntimeException failure;

        private ReentrantAppender(final boolean ignoreExceptions) {
            super("reentrant", null, null, ignoreExceptions, Property.EMPTY_ARRAY);
            setHandler(new ErrorHandler() {
                @Override
                public void error(final String msg) {
                    errors.add(msg);
                }

                @Override
                public void error(final String msg, final Throwable t) {
                    errors.add(msg);
                }

                @Override
                public void error(final String msg, final LogEvent event, final Throwable t) {
                    errors.add(msg);
                }
            });
            start();
        }

        @Override
        public void append(final LogEvent event) {
            appendCount++;
            if (failure != null) {
                throw failure;
            }
            if (control != null) {
                control.callAppender(event);
            }
        }
    }

    @Test
    void recursive_call_is_skipped_and_reported() {
        final ReentrantAppender appender = new ReentrantAppender(true);
        final AppenderControl control = new AppenderControl(appender, null, null);
        appender.control = control;

        control.callAppender(EVENT);

        assertThat(appender.appendCount).isEqualTo(1);
        assertThat(appender.errors).containsExactly("Recursive call to appender reentrant");

        // The guard is released after each call
        control.callAppender(EVENT);
        assertThat(appender.appendCount).isEqualTo(2);
        assertThat(appender.errors).hasSize(2);
    }

    @Test
    void guard_is_released_after_an_ignored_exception() {
        final ReentrantAppender appender = new ReentrantAppender(true);
        final AppenderControl control = new AppenderControl(appender, null, null);
        appender.failure = new IllegalStateException("expected");

        control.callAppender(EVENT);
        appender.failure = null;
        control.callAppender(EVENT);

        assertThat(appender.appendCount).isEqualTo(2);
        assertThat(appender.errors).containsExactly("An exception occurred processing Appender reentrant");
    }

    @Test
    void guard_is_released_after_a_propagated_exception() {
        final ReentrantAppender appender = new ReentrantAppender(false);
        final AppenderControl control = new AppenderControl(appender, null, null);
        appender.failure = new IllegalStateException("expected");

        assertThatThrownBy(() -> control.callAppender(EVENT)).isSameAs(appender.failure);
        appender.failure = null;
        control.callAppender(EVENT);

        assertThat(appender.appendCount).isEqualTo(2);
    }

    @Test
    void guard_is_per_thread() throws InterruptedException {
        final ReentrantAppender appender = new ReentrantAppender(true);
        final AppenderControl control = new AppenderControl(appender, null, null);
        final AtomicReference<Throwable> otherThreadFailure = new AtomicReference<>();
        // While this thread is inside the appender, another thread must still be able to call it
        appender.control = new AppenderControl(new ReentrantAppender(true), null, null) {

            private boolean called;

            @Override
            public void callAppender(final LogEvent event) {
                // Only the outer call starts a thread (set before `Thread.start()`, so the other thread sees it)
                if (called) {
                    return;
                }
                called = true;
                final Thread thread = new Thread(() -> {
                    try {
                        control.callAppender(EVENT);
                    } catch (final Throwable error) {
                        otherThreadFailure.set(error);
                    }
                });
                thread.start();
                try {
                    thread.join();
                } catch (final InterruptedException error) {
                    Thread.currentThread().interrupt();
                }
            }
        };

        control.callAppender(EVENT);

        assertThat(otherThreadFailure.get()).isNull();
        // Both calls reached the appender, and none was reported as recursive
        assertThat(appender.appendCount).isEqualTo(2);
        assertThat(appender.errors).isEmpty();
    }
}
