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

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.impl.Log4jLogEvent;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Tests {@link PatternFormatter}, in particular level converters, whose formatted output is computed up front.
 */
class PatternFormatterTest {

    /** Exists before the level converters are created. */
    private static final Level CUSTOM_LEVEL = Level.forName("PATTERN_FORMATTER_TEST", 350);

    private static final FormattingInfo[] FORMATTING_INFOS = {
        FormattingInfo.getDefault(),
        new FormattingInfo(true, 5, Integer.MAX_VALUE, false), // %-5level
        new FormattingInfo(false, 5, Integer.MAX_VALUE, false), // %5level
        new FormattingInfo(true, 12, Integer.MAX_VALUE, false), // %-12level (padding longer than 8 spaces)
        new FormattingInfo(false, 0, 1, true), // %.1level
        new FormattingInfo(false, 0, 2, false), // %.-2level
        new FormattingInfo(true, 10, 3, true), // %-10.3level
        new FormattingInfo(false, 7, Integer.MAX_VALUE, false, true), // %07level
    };

    private static final String[][] CONVERTER_OPTIONS = {
        null, {"length=1"}, {"lowerCase=true"}, {"WARN=Warning,INFO=Information"}, {"length=2,lowerCase=true"}
    };

    static Stream<Arguments> cases() {
        final List<Arguments> cases = new ArrayList<>();
        for (final String[] options : CONVERTER_OPTIONS) {
            for (final FormattingInfo formattingInfo : FORMATTING_INFOS) {
                cases.add(Arguments.of(options, formattingInfo));
            }
        }
        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("cases")
    void level_output_matches_converter_followed_by_formatting_info(
            final String[] options, final FormattingInfo formattingInfo) {
        final LevelPatternConverter converter = LevelPatternConverter.newInstance(options);
        final PatternFormatter formatter = new PatternFormatter(converter, formattingInfo);
        assertThat(formatter.getConverter()).isSameAs(converter);
        assertThat(formatter.getFormattingInfo()).isSameAs(formattingInfo);

        // Created after the converter, so it is unknown to a converter with a level map
        final Level lateCustomLevel = Level.forName("PATTERN_FORMATTER_TEST_LATE", 450);
        final List<Level> levels = new ArrayList<>(Arrays.asList(Level.values()));
        levels.add(CUSTOM_LEVEL);
        levels.add(lateCustomLevel);
        for (int round = 0; round < 2; round++) {
            for (final Level level : levels) {
                final LogEvent event =
                        Log4jLogEvent.newBuilder().setLevel(level).build();
                // Non-empty prefix to verify that formatting only touches the field
                final StringBuilder expected = new StringBuilder("prefix ");
                converter.format(event, expected);
                formattingInfo.format("prefix ".length(), expected);
                final StringBuilder actual = new StringBuilder("prefix ");
                formatter.format(event, actual);
                assertThat(actual)
                        .as("level %s, options %s, %s", level, Arrays.toString(options), formattingInfo)
                        .hasToString(expected.toString());
            }
        }
    }
}
