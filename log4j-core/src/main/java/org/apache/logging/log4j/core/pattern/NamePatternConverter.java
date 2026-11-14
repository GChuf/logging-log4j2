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

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.apache.logging.log4j.util.PerformanceSensitive;

/**
 * Abstract base class for other pattern converters which can return only parts of their name.
 */
@PerformanceSensitive("allocation")
public abstract class NamePatternConverter extends LogEventPatternConverter {

    /**
     * Maximum number of distinct names whose abbreviation is cached.
     * <p>
     * Logger and class names form a small, fixed set in most applications, so the cache normally holds every name.
     * Names seen after the cache is full are abbreviated on every call, like without a cache, so neither memory nor
     * allocation grows without bound when an application creates logger names dynamically.
     * </p>
     */
    static final int MAX_CACHED_ABBREVIATIONS = 4096;

    /**
     * Abbreviator.
     */
    private final NameAbbreviator abbreviator;

    /**
     * Abbreviated names keyed by the original name, or {@code null} if the abbreviator does not change the name.
     */
    private final ConcurrentMap<String, String> abbreviations;

    /**
     * Constructor.
     *
     * @param name    name of converter.
     * @param style   style name for associated output.
     * @param options options, may be null, first element will be interpreted as an abbreviation pattern.
     */
    protected NamePatternConverter(final String name, final String style, final String[] options) {
        super(name, style);

        if (options != null && options.length > 0) {
            abbreviator = NameAbbreviator.getAbbreviator(options[0]);
        } else {
            abbreviator = NameAbbreviator.getDefaultAbbreviator();
        }
        abbreviations = abbreviator == NameAbbreviator.getDefaultAbbreviator() ? null : new ConcurrentHashMap<>();
    }

    /**
     * Abbreviate name in string buffer.
     *
     * @param original string containing name.
     * @param destination the StringBuilder to write to
     */
    protected final void abbreviate(final String original, final StringBuilder destination) {
        if (abbreviations == null || original == null) {
            abbreviator.abbreviate(original, destination);
            return;
        }
        final String abbreviation = abbreviations.get(original);
        if (abbreviation != null) {
            destination.append(abbreviation);
        } else if (abbreviations.size() < MAX_CACHED_ABBREVIATIONS) {
            // Allocates only once per distinct name
            final StringBuilder builder = new StringBuilder(original.length());
            abbreviator.abbreviate(original, builder);
            final String computed = builder.toString();
            abbreviations.putIfAbsent(original, computed);
            destination.append(computed);
        } else {
            abbreviator.abbreviate(original, destination);
        }
    }
}
