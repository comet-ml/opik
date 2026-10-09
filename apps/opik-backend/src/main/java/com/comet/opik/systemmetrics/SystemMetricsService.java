package com.comet.opik.systemmetrics;

import com.comet.opik.infrastructure.SystemMetricsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ServiceUnavailableException;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

@Singleton
public class SystemMetricsService {

    private static final Duration MAX_SAMPLE_AGE = Duration.ofDays(7);
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    private static final Pattern ATTRIBUTE_KEY = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,63}");
    private static final Pattern METRIC_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_.-]{0,127}");

    private final @NonNull SystemMetricsDAO dao;
    private final @NonNull SystemMetricsConfig config;

    @Inject
    public SystemMetricsService(
            @NonNull SystemMetricsDAO dao,
            @NonNull @Config("systemMetrics") SystemMetricsConfig config) {
        this.dao = dao;
        this.config = config;
    }

    public long ingest(String workspaceId, UUID projectId, SystemMetricBatch batch) {
        ensureEnabled();
        if (batch.metrics().size() > config.getMaxBatchSize()) {
            throw new BadRequestException("System metrics batch exceeds configured maximum");
        }
        var now = Instant.now();
        var normalized = batch.metrics().stream()
                .map(point -> validateAndNormalize(point, now))
                .toList();
        return dao.insertBatch(workspaceId, projectId, normalized).blockOptional().orElse(0L);
    }

    public SystemMetricSeries query(
            String workspaceId,
            UUID projectId,
            String serviceInstanceId,
            String metricName,
            Instant from,
            Instant to) {
        ensureEnabled();
        validateRange(from, to);
        validateIdentifier(serviceInstanceId, "service_instance_id", 128);
        if (metricName == null || !METRIC_NAME.matcher(metricName).matches()) {
            throw new BadRequestException("metric_name is invalid");
        }

        var points = dao.find(
                workspaceId,
                projectId,
                serviceInstanceId,
                metricName,
                from,
                to,
                config.getMaxQueryPoints() + 1)
                .blockOptional()
                .orElse(List.of());
        var truncated = points.size() > config.getMaxQueryPoints();
        var selectedPoints = truncated ? points.subList(0, config.getMaxQueryPoints()) : points;
        var unit = selectedPoints.isEmpty() ? "" : selectedPoints.getFirst().unit();
        var samples = selectedPoints.stream()
                .map(point -> new SystemMetricSample(point.timestamp(), point.value(), point.attributes()))
                .toList();
        return new SystemMetricSeries(projectId, serviceInstanceId, metricName, unit, from, to, truncated, samples);
    }

    public SystemMetricInstances listInstances(String workspaceId, UUID projectId) {
        ensureEnabled();
        var from = Instant.now().minusMillis(config.getMaxQueryRange().toMilliseconds());
        var instances = dao.findInstances(workspaceId, projectId, from, 1_000)
                .blockOptional()
                .orElse(List.of());
        return new SystemMetricInstances(instances);
    }

    private SystemMetricPoint validateAndNormalize(SystemMetricPoint point, Instant now) {
        if (!Double.isFinite(point.value())) {
            throw new BadRequestException("System metric value must be finite");
        }
        if (point.timestamp().isBefore(now.minus(MAX_SAMPLE_AGE))
                || point.timestamp().isAfter(now.plus(MAX_FUTURE_SKEW))) {
            throw new BadRequestException("System metric timestamp is outside the accepted window");
        }
        var rawAttributes = point.attributes() == null ? Map.<String, String>of() : point.attributes();
        if (rawAttributes.size() > 16) {
            throw new BadRequestException("System metrics support at most 16 attributes per point");
        }
        rawAttributes.forEach((key, value) -> {
            if (key == null || !ATTRIBUTE_KEY.matcher(key).matches()) {
                throw new BadRequestException("Invalid system metric attribute key");
            }
            if (value == null || value.length() > 256) {
                throw new BadRequestException("System metric attribute value exceeds 256 characters");
            }
        });
        var attributes = Map.copyOf(rawAttributes);
        return new SystemMetricPoint(
                point.sampleId(),
                point.serviceName(),
                point.serviceInstanceId(),
                point.agentId() == null ? "" : point.agentId(),
                point.metricName(),
                point.unit(),
                point.timestamp(),
                point.value(),
                attributes);
    }

    private void validateRange(Instant from, Instant to) {
        if (from == null || to == null) {
            throw new BadRequestException("from and to are required");
        }
        if (!from.isBefore(to)) {
            throw new BadRequestException("from must be before to");
        }
        if (Duration.between(from, to).toMillis() > config.getMaxQueryRange().toMilliseconds()) {
            throw new BadRequestException("Requested system metrics range exceeds configured maximum");
        }
    }

    private void validateIdentifier(String value, String field, int maxLength) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new BadRequestException(field + " is invalid");
        }
    }

    private void ensureEnabled() {
        if (!config.isEnabled()) {
            throw new ServiceUnavailableException("System metrics are disabled");
        }
    }
}
