package com.comet.opik.infrastructure.instrumentation;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Centralized service for creating and managing OpenTelemetry metrics.
 * 
 * This service provides a unified interface for instrumenting the application with
 * OpenTelemetry metrics. It handles meter creation and caches metric instruments
 * to avoid recreation overhead.
 */
@Slf4j
@Singleton
public class InstrumentationService {

    private static final String METER_NAME = "opik-backend";

    private final Meter meter;
    private final Map<String, LongCounter> counterCache = new ConcurrentHashMap<>();
    private final Map<String, DoubleHistogram> histogramCache = new ConcurrentHashMap<>();

    public InstrumentationService() {
        this.meter = GlobalOpenTelemetry.get().getMeter(METER_NAME);
        log.info("Initialized InstrumentationService with meter: '{}'", METER_NAME);
    }

    /**
     * Creates or retrieves a cached long counter metric.
     * 
     * @param name the metric name
     * @param description the metric description
     * @param unit the metric unit (e.g., "requests", "errors")
     * @return the counter instance
     */
    public LongCounter createCounter(@NonNull String name, @NonNull String description, @NonNull String unit) {
        return counterCache.computeIfAbsent(name, key -> {
            log.debug("Creating new counter: '{}' ({})", name, description);
            return meter.counterBuilder(name)
                    .setDescription(description)
                    .setUnit(unit)
                    .build();
        });
    }

    /**
     * Creates or retrieves a cached double histogram metric.
     * 
     * @param name the metric name
     * @param description the metric description
     * @param unit the metric unit (e.g., "seconds", "bytes")
     * @return the histogram instance
     */
    public DoubleHistogram createHistogram(@NonNull String name, @NonNull String description,
            @NonNull String unit) {
        return histogramCache.computeIfAbsent(name, key -> {
            log.debug("Creating new histogram: '{}' ({})", name, description);
            return meter.histogramBuilder(name)
                    .setDescription(description)
                    .setUnit(unit)
                    .build();
        });
    }

    /**
     * Records a value to a counter with the specified attributes.
     * 
     * @param counter the counter to increment
     * @param value the value to add
     * @param attributes the attributes to attach
     */
    public void recordCounter(@NonNull LongCounter counter, long value, @NonNull Attributes attributes) {
        try {
            counter.add(value, attributes);
        } catch (Exception e) {
            log.warn("Failed to record counter metric", e);
        }
    }

    /**
     * Records a value to a histogram with the specified attributes.
     * 
     * @param histogram the histogram to record to
     * @param value the value to record
     * @param attributes the attributes to attach
     */
    public void recordHistogram(@NonNull DoubleHistogram histogram, double value, @NonNull Attributes attributes) {
        try {
            histogram.record(value, attributes);
        } catch (Exception e) {
            log.warn("Failed to record histogram metric", e);
        }
    }

    /**
     * Returns the underlying OpenTelemetry Meter for advanced use cases.
     * 
     * @return the meter instance
     */
    public Meter getMeter() {
        return meter;
    }
}

