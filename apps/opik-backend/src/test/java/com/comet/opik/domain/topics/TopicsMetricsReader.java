package com.comet.opik.domain.topics;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.Meter;
import io.opentelemetry.sdk.metrics.SdkMeterProvider;
import io.opentelemetry.sdk.testing.exporter.InMemoryMetricReader;

import java.util.List;
import java.util.stream.IntStream;

/** The topics metrics a test recorded, read from an in-memory OTel reader. */
final class TopicsMetricsReader {

    /** One point of a metric: a counter's sum or a histogram's sum, for one set of attributes. */
    record Point(double value, Attributes attributes) {

        String get(String key) {
            return attributes.get(AttributeKey.stringKey(key));
        }
    }

    private TopicsMetricsReader() {
    }

    /** Metrics that record into {@code reader}. */
    static TopicsMetrics metrics(InMemoryMetricReader reader) {
        return new TopicsMetrics(meter(reader));
    }

    /** A meter that records into {@code reader}. */
    static Meter meter(InMemoryMetricReader reader) {
        return SdkMeterProvider.builder().registerMetricReader(reader).build().get("opik.topics");
    }

    static List<Point> points(InMemoryMetricReader reader, String name) {
        return reader.collectAllMetrics().stream()
                .filter(metric -> metric.getName().equals(name))
                .flatMap(metric -> switch (metric.getType()) {
                    case LONG_SUM -> metric.getLongSumData().getPoints().stream()
                            .map(point -> new Point(point.getValue(), point.getAttributes()));
                    case DOUBLE_SUM -> metric.getDoubleSumData().getPoints().stream()
                            .map(point -> new Point(point.getValue(), point.getAttributes()));
                    case HISTOGRAM -> metric.getHistogramData().getPoints().stream()
                            .map(point -> new Point(point.getSum(), point.getAttributes()));
                    default -> throw new IllegalStateException("unexpected metric type " + metric.getType());
                })
                .toList();
    }

    /** The metric's total over the points whose attributes hold every given key and value pair. */
    static double sum(InMemoryMetricReader reader, String name, String... pairs) {
        return points(reader, name).stream()
                .filter(point -> IntStream.range(0, pairs.length / 2)
                        .allMatch(i -> pairs[2 * i + 1].equals(point.get(pairs[2 * i]))))
                .mapToDouble(Point::value)
                .sum();
    }
}
