/*
 * Copyright 2016 Palantir Technologies, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.palantir.tritium.metrics;

import static com.google.common.base.Preconditions.checkNotNull;

import com.codahale.metrics.Gauge;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricRegistry;
import com.google.common.cache.Cache;
import com.google.common.collect.ImmutableSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Utilities for working with {@link MetricRegistry} instances.
 */
public final class MetricRegistries {

    private static final Logger LOGGER = LoggerFactory.getLogger(MetricRegistries.class);

    private MetricRegistries() {
        throw new UnsupportedOperationException();
    }

    /**
     * Create metric registry which produces timers and histograms backed by high dynamic range histograms.
     *
     * @return metric registry
     */
    public static MetricRegistry createWithHdrHistogramReservoirs() {
        // Use HDR Histogram reservoir histograms and timers, instead of default exponentially decaying reservoirs,
        // see http://taint.org/2014/01/16/145944a.html
        return HdrHistogramMetricRegistry.create();
    }

    @SuppressWarnings("unchecked")
    static <T extends Metric> T getOrAdd(MetricRegistry metrics, String name, MetricBuilder<T> builder) {
        checkNotNull(metrics);
        checkNotNull(name);
        checkNotNull(builder);

        final Metric metric = metrics.getMetrics().get(name);
        if (metric == null) {
            try {
                return metrics.register(name, builder.newMetric());
            } catch (IllegalArgumentException e) {
                final Metric added = metrics.getMetrics().get(name);
                if (builder.isInstance(added)) {
                    return (T) added;
                }
            }
        } else if (builder.isInstance(metric)) {
            return (T) metric;
        }
        throw new IllegalArgumentException(name + " is already used for a different type of metric");
    }


    /**
     * Register specified cache with the given metric registry.
     *
     * @param registry metric registry
     * @param cache cache to instrument
     * @param metricsPrefix metrics prefix
     */
    public static <C extends Cache<?, ?>> void registerCache(MetricRegistry registry,
                                                             Cache<?, ?> cache,
                                                             String metricsPrefix) {
        checkNotNull(registry, "metric registry");
        checkNotNull(metricsPrefix, "prefix");
        checkNotNull(cache, "cache");

        CacheMetricSet cacheMetrics = new CacheMetricSet(cache, metricsPrefix);
        for (Entry<String, Metric> entry : cacheMetrics.getMetrics().entrySet()) {
            registerSafe(registry, entry.getKey(), entry.getValue());
        }
    }

    /**
     * Ensures a {@link Metric} is registered to a {@link MetricRegistry} with the supplied {@code name}. If there is an
     * existing {@link Metric} registered to {@code name} with the same implemented set of interfaces as {@code metric}
     * then it's returned. Otherwise {@code metric} is registered and returned.
     * <p>
     * This is intended to imitate the semantics of {@link MetricRegistry#counter(String)} and should only be used for
     * {@link Metric} implementations that can't be registered/created in that manner (because it does not actually
     * guarantee that the registered {@link Metric} matches the input {@code metric}).
     * <p>
     * For example, this may be useful for registering {@link Gauge}s which might cause issues from being added multiple
     * times to a static {@link MetricRegistry} in a unit test
     *
     * @throws IllegalArgumentException if there is already a {@link Metric} registered that doesn't implement the same
     *         interfaces as {@code metric}
     */
    public static <T extends Metric> T registerSafe(MetricRegistry registry, String name, T metric) {
        Map<String, Metric> metrics = registry.getMetrics();
        Metric existingMetric = metrics.get(name);
        if (existingMetric == null) {
            return registry.register(name, metric);
        } else {
            LOGGER.warn("Metric already registered at this name."
                    + " Name: {}, existing metric: {}", name, existingMetric);

            Set<Class<?>> existingMetricInterfaces = ImmutableSet.copyOf(existingMetric.getClass().getInterfaces());
            Set<Class<?>> newMetricInterfaces = ImmutableSet.copyOf(metric.getClass().getInterfaces());
            if (!existingMetricInterfaces.equals(newMetricInterfaces)) {
                throw new IllegalArgumentException(
                        "Metric already registered at this name that implements a different set of interfaces."
                                + " Name: " + name + ", existing metric: " + existingMetric);
            }

            @SuppressWarnings("unchecked")
            T registeredMetric = (T) existingMetric;
            return registeredMetric;
        }
    }
}