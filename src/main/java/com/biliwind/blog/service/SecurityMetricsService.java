package com.biliwind.blog.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Shared low-cardinality counters for security and degradation decisions. */
@ApplicationScoped
public class SecurityMetricsService {

    private final ConcurrentMap<String, Counter> counters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicLong> gauges = new ConcurrentHashMap<>();

    @Inject
    MeterRegistry meterRegistry;

    public void increment(String name) {
        counters.computeIfAbsent(name, currentName -> Counter.builder("windblog." + currentName)
                .description("WindBlog security and reliability counter")
                .register(meterRegistry)).increment();
    }

    public void increment(String name, String reason) {
        String safeReason = reason == null || reason.isBlank() ? "unknown" : reason;
        counters.computeIfAbsent(name + "." + safeReason, currentName -> Counter.builder("windblog." + name)
                .description("WindBlog security and reliability counter")
                .tag("reason", safeReason)
                .register(meterRegistry)).increment();
    }

    public void setGauge(String name, long value) {
        AtomicLong gaugeValue = gauges.computeIfAbsent(name, currentName -> {
            AtomicLong created = new AtomicLong();
            io.micrometer.core.instrument.Gauge.builder("windblog." + currentName,
                            created, AtomicLong::doubleValue)
                    .description("WindBlog security and reliability gauge")
                    .register(meterRegistry);
            return created;
        });
        gaugeValue.set(value);
    }
}
