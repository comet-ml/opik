package com.comet.opik.domain.topics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.DoubleGaugeBuilder;
import io.opentelemetry.api.metrics.DoubleHistogram;
import io.opentelemetry.api.metrics.DoubleHistogramBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.LongGaugeBuilder;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.api.metrics.ObservableDoubleMeasurement;
import io.opentelemetry.api.metrics.ObservableLongMeasurement;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static io.opentelemetry.api.common.AttributeKey.stringKey;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * A {@link Meter} that keeps every counter add and histogram record by metric name, and every gauge callback, so a test
 * reads what was recorded without the OTel SDK.
 */
final class TopicsTestMeter {

    /** One add or record, or one gauge value at a scrape. */
    record Point(double value, Attributes attributes) {

        String get(String key) {
            return attributes.get(stringKey(key));
        }
    }

    final Meter meter = mock(Meter.class);
    private final Map<String, List<Point>> points = new ConcurrentHashMap<>();
    private final Map<String, Consumer<ObservableDoubleMeasurement>> doubleGauges = new ConcurrentHashMap<>();
    private final Map<String, Consumer<ObservableLongMeasurement>> longGauges = new ConcurrentHashMap<>();

    TopicsTestMeter() {
        when(meter.counterBuilder(anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            var counter = mock(LongCounter.class);
            doAnswer(add -> record(name, number(add.getArgument(0)), add.getArgument(1))).when(counter)
                    .add(anyLong(), any(Attributes.class));
            doAnswer(add -> record(name, number(add.getArgument(0)), Attributes.empty())).when(counter).add(anyLong());
            var builder = mock(LongCounterBuilder.class, RETURNS_SELF);
            doReturn(counter).when(builder).build();
            return builder;
        });
        when(meter.histogramBuilder(anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            var histogram = mock(DoubleHistogram.class);
            doAnswer(add -> record(name, number(add.getArgument(0)), add.getArgument(1))).when(histogram)
                    .record(anyDouble(), any(Attributes.class));
            doAnswer(add -> record(name, number(add.getArgument(0)), Attributes.empty())).when(histogram)
                    .record(anyDouble());
            var builder = mock(DoubleHistogramBuilder.class, RETURNS_SELF);
            doReturn(histogram).when(builder).build();
            return builder;
        });
        when(meter.gaugeBuilder(anyString())).thenAnswer(invocation -> {
            String name = invocation.getArgument(0);
            var builder = mock(DoubleGaugeBuilder.class, RETURNS_SELF);
            doAnswer(build -> doubleGauges.put(name, build.getArgument(0))).when(builder).buildWithCallback(any());
            var longs = mock(LongGaugeBuilder.class, RETURNS_SELF);
            doAnswer(build -> longGauges.put(name, build.getArgument(0))).when(longs).buildWithCallback(any());
            doReturn(longs).when(builder).ofLongs();
            return builder;
        });
    }

    private Object record(String name, double value, Attributes attributes) {
        points.computeIfAbsent(name, key -> new CopyOnWriteArrayList<>()).add(new Point(value, attributes));
        return null;
    }

    /** Every add or record of the metric so far. */
    List<Point> points(String name) {
        return List.copyOf(points.getOrDefault(name, List.of()));
    }

    /** The sum of the metric's adds whose attributes hold every given pair. */
    double sum(String name, String... pairs) {
        return points(name).stream().filter(point -> matches(point.attributes(), pairs))
                .mapToDouble(Point::value).sum();
    }

    boolean hasGauge(String name) {
        return doubleGauges.containsKey(name) || longGauges.containsKey(name);
    }

    /** One scrape of the gauge: the values its callback records now. */
    List<Point> observe(String name) {
        var observed = new ArrayList<Point>();
        if (doubleGauges.containsKey(name)) {
            var measurement = mock(ObservableDoubleMeasurement.class);
            doAnswer(invocation -> observed.add(new Point(number(invocation.getArgument(0)), Attributes.empty())))
                    .when(measurement).record(anyDouble());
            doAnswer(
                    invocation -> observed.add(new Point(number(invocation.getArgument(0)), invocation.getArgument(1))))
                    .when(measurement).record(anyDouble(), any(Attributes.class));
            doubleGauges.get(name).accept(measurement);
        }
        if (longGauges.containsKey(name)) {
            var measurement = mock(ObservableLongMeasurement.class);
            doAnswer(invocation -> observed.add(new Point(number(invocation.getArgument(0)), Attributes.empty())))
                    .when(measurement).record(anyLong());
            doAnswer(invocation -> observed.add(new Point(number(invocation.getArgument(0)),
                    invocation.getArgument(1)))).when(measurement).record(anyLong(), any(Attributes.class));
            longGauges.get(name).accept(measurement);
        }
        return observed;
    }

    private static double number(Object value) {
        return ((Number) value).doubleValue();
    }

    static boolean matches(Attributes attributes, String... pairs) {
        for (int i = 0; i < pairs.length; i += 2) {
            AttributeKey<String> key = stringKey(pairs[i]);
            if (!pairs[i + 1].equals(attributes.get(key))) {
                return false;
            }
        }
        return true;
    }
}
