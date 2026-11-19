package com.comet.opik.domain.topics;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.DoubleGaugeBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** A {@link Meter} whose instruments are mocks kept by name, and whose gauges can be scraped on demand. */
final class TopicsTestMeter {

    final Meter meter = mock(Meter.class);
    private final Map<String, LongCounter> counters = new ConcurrentHashMap<>();
    private final Map<String, DoubleHistogram> histograms = new ConcurrentHashMap<>();
    private final Map<String, Consumer<ObservableDoubleMeasurement>> gauges = new ConcurrentHashMap<>();

    @SuppressWarnings("unchecked")
    TopicsTestMeter() {
        when(meter.counterBuilder(anyString())).thenAnswer(invocation -> {
            var builder = mock(LongCounterBuilder.class, RETURNS_SELF);
            doReturn(counter(invocation.getArgument(0))).when(builder).build();
            return builder;
        });
        when(meter.histogramBuilder(anyString())).thenAnswer(invocation -> {
            var builder = mock(DoubleHistogramBuilder.class, RETURNS_SELF);
            doReturn(histogram(invocation.getArgument(0))).when(builder).build();
            return builder;
        });
        when(meter.gaugeBuilder(anyString())).thenAnswer(invocation -> {
            var builder = mock(DoubleGaugeBuilder.class, RETURNS_SELF);
            String name = invocation.getArgument(0);
            doAnswer(build -> {
                gauges.put(name, build.getArgument(0));
                return null;
            }).when(builder).buildWithCallback(any(Consumer.class));
            return builder;
        });
    }

    /** Workspace names as {@link #workspace} gives them. */
    static TopicsWorkspaceNames names() {
        return new TopicsWorkspaceNames(workspaceId -> "name-" + workspaceId, Clock.systemUTC());
    }

    /** Both workspace attributes, with the name {@link #names()} gives. */
    static AttributesBuilder workspace(String workspaceId) {
        return TopicsWorkspaceNames.builder(workspaceId, "name-" + workspaceId);
    }

    LongCounter counter(String name) {
        return counters.computeIfAbsent(name, key -> mock(LongCounter.class));
    }

    DoubleHistogram histogram(String name) {
        return histograms.computeIfAbsent(name, key -> mock(DoubleHistogram.class));
    }

    /** One scrape of the gauge {@code name}: its value per workspace. */
    Map<String, Double> observeGauge(String name) {
        var values = new HashMap<String, Double>();
        var measurement = mock(ObservableDoubleMeasurement.class);
        doAnswer(invocation -> {
            Attributes attributes = invocation.getArgument(1);
            values.put(attributes.get(stringKey("workspace_id")), invocation.getArgument(0));
            return null;
        }).when(measurement).record(anyDouble(), any(Attributes.class));
        gauges.get(name).accept(measurement);
        return values;
    }
}
