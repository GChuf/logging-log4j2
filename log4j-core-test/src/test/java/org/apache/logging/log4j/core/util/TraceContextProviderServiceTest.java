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
package org.apache.logging.log4j.core.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.apache.logging.log4j.spi.TraceContextProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Tests the {@link TraceContextProviderService} methods that take an explicit provider.
 */
class TraceContextProviderServiceTest {

    @AfterEach
    void tearDown() {
        TraceContextProviderService.setActiveProvider(null);
    }

    @Test
    void valuesComeFromTheGivenProvider() {
        final TraceContextProvider provider = provider("trace", "span", "01");

        assertThat(TraceContextProviderService.getTraceId(provider)).isEqualTo("trace");
        assertThat(TraceContextProviderService.getSpanId(provider)).isEqualTo("span");
        assertThat(TraceContextProviderService.getTraceFlags(provider)).isEqualTo("01");
    }

    @Test
    void valuesMatchTheActiveProviderLookups() {
        TraceContextProviderService.setActiveProvider(provider("trace", "span", "01"));
        final TraceContextProvider active = TraceContextProviderService.getActiveProvider();

        assertThat(TraceContextProviderService.getTraceId(active)).isEqualTo(TraceContextProviderService.getTraceId());
        assertThat(TraceContextProviderService.getSpanId(active)).isEqualTo(TraceContextProviderService.getSpanId());
        assertThat(TraceContextProviderService.getTraceFlags(active))
                .isEqualTo(TraceContextProviderService.getTraceFlags());
    }

    @Test
    void failingValueIsNullWithoutAffectingTheOthers() {
        final TraceContextProvider provider = new TraceContextProvider() {
            @Override
            public String getTraceId() {
                throw new IllegalStateException("Simulated provider failure");
            }

            @Override
            public String getSpanId() {
                return "span";
            }

            @Override
            public String getTraceFlags() {
                throw new LinkageError("Simulated provider failure");
            }
        };

        assertThat(TraceContextProviderService.getTraceId(provider)).isNull();
        assertThat(TraceContextProviderService.getSpanId(provider)).isEqualTo("span");
        assertThat(TraceContextProviderService.getTraceFlags(provider)).isNull();
    }

    @Test
    void resetProviderReturnsNoValues() {
        // Resetting selects the no-op provider, even though this module registers a test provider as a service
        TraceContextProviderService.setActiveProvider(null);
        final TraceContextProvider active = TraceContextProviderService.getActiveProvider();

        assertThat(TraceContextProviderService.getTraceId(active)).isNull();
        assertThat(TraceContextProviderService.getSpanId(active)).isNull();
        assertThat(TraceContextProviderService.getTraceFlags(active)).isNull();
    }

    private static TraceContextProvider provider(final String traceId, final String spanId, final String flags) {
        return new TraceContextProvider() {
            @Override
            public String getTraceId() {
                return traceId;
            }

            @Override
            public String getSpanId() {
                return spanId;
            }

            @Override
            public String getTraceFlags() {
                return flags;
            }
        };
    }
}
