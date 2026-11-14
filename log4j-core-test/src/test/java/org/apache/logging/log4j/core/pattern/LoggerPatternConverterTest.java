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
package org.apache.logging.log4j.core.pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests {@link LoggerPatternConverter}, including the cache of abbreviated names in {@link NamePatternConverter}.
 */
class LoggerPatternConverterTest {

    private static String format(final LogEventPatternConverter converter, final String loggerName) {
        final LogEvent event =
                Log4jLogEvent.newBuilder().setLoggerName(loggerName).build();
        // Non-empty prefix to verify that the converter appends instead of overwriting
        final StringBuilder sb = new StringBuilder(">");
        converter.format(event, sb);
        return sb.substring(1);
    }

    private static String abbreviate(final String pattern, final String name) {
        final StringBuilder sb = new StringBuilder();
        NameAbbreviator.getAbbreviator(pattern).abbreviate(name, sb);
        return sb.toString();
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.", "1", "-1", "1~.2~", "*.", "0.0.1", "1.1.*"})
    void repeatedFormattingMatchesAbbreviator(final String pattern) {
        final LoggerPatternConverter converter = LoggerPatternConverter.newInstance(new String[] {pattern});
        final String[] names = {"org.apache.logging.log4j.core.Foo", "Foo", "a.b.Foo", "", "org.apache.Bar"};
        for (int round = 0; round < 3; round++) {
            for (final String name : names) {
                assertEquals(abbreviate(pattern, name), format(converter, name), pattern + " on " + name);
            }
        }
    }

    @Test
    void namesBeyondCacheCapacityAreStillAbbreviated() {
        final LoggerPatternConverter converter = LoggerPatternConverter.newInstance(new String[] {"1."});
        final int count = NamePatternConverter.MAX_CACHED_ABBREVIATIONS + 100;
        for (int round = 0; round < 2; round++) {
            for (int i = 0; i < count; i++) {
                final String name = "org.example.pkg" + i + ".Logger" + i;
                assertEquals("o.e.p.Logger" + i, format(converter, name), name);
            }
        }
    }

    @Test
    void withoutOptionsNameIsUnchanged() {
        final LoggerPatternConverter converter = LoggerPatternConverter.newInstance(null);
        assertEquals("org.apache.logging.log4j.core.Foo", format(converter, "org.apache.logging.log4j.core.Foo"));
    }
}
