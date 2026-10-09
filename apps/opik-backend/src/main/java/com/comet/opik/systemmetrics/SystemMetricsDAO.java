package com.comet.opik.systemmetrics;

import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Result;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.comet.opik.utils.ClickHouseDateTimeFormat.formatMillis;

@ImplementedBy(SystemMetricsDAOImpl.class)
public interface SystemMetricsDAO {

    Mono<Long> insertBatch(String workspaceId, UUID projectId, List<SystemMetricPoint> points);

    Mono<List<StoredMetricPoint>> find(
            String workspaceId,
            UUID projectId,
            String serviceInstanceId,
            String metricName,
            Instant from,
            Instant to,
            int limit);

    Mono<List<SystemMetricInstance>> findInstances(
            String workspaceId,
            UUID projectId,
            Instant from,
            int limit);

    record StoredMetricPoint(Instant timestamp, double value, String unit, Map<String, String> attributes) {
    }
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class SystemMetricsDAOImpl implements SystemMetricsDAO {

    private static final String INSERT = """
            INSERT INTO system_metrics(
                sample_id,
                workspace_id,
                project_id,
                service_name,
                service_instance_id,
                agent_id,
                metric_name,
                unit,
                timestamp,
                value,
                attributes
            )
            FORMAT Values
                <items:{item |
                    (
                        :sample_id<item.index>,
                        :workspace_id,
                        :project_id,
                        :service_name<item.index>,
                        :service_instance_id<item.index>,
                        :agent_id<item.index>,
                        :metric_name<item.index>,
                        :unit<item.index>,
                        :timestamp<item.index>,
                        :value<item.index>,
                        :attributes<item.index>
                    )
                    <if(item.hasNext)>,<endif>
                }>
            """;

    private static final String FIND = """
            SELECT timestamp, value, unit, attributes
            FROM system_metrics FINAL
            WHERE workspace_id = :workspace_id
              AND project_id = :project_id
              AND service_instance_id = :service_instance_id
              AND metric_name = :metric_name
              AND timestamp >= :from
              AND timestamp <= :to
            ORDER BY timestamp
            LIMIT :limit
            """;

    private static final String FIND_INSTANCES = """
            SELECT
                service_instance_id,
                argMax(service_name, timestamp) AS service_name,
                argMax(agent_id, timestamp) AS agent_id,
                max(timestamp) AS last_seen
            FROM system_metrics FINAL
            WHERE workspace_id = :workspace_id
              AND project_id = :project_id
              AND timestamp >= :from
            GROUP BY service_instance_id
            ORDER BY last_seen DESC
            LIMIT :limit
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;

    @Override
    public Mono<Long> insertBatch(
            @NonNull String workspaceId,
            @NonNull UUID projectId,
            @NonNull List<SystemMetricPoint> points) {
        return asyncTemplate.nonTransaction(connection -> {
            var template = TemplateUtils.getBatchSql(INSERT, points.size());
            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId)
                    .bind("project_id", projectId);

            for (var i = 0; i < points.size(); i++) {
                var point = points.get(i);
                statement.bind("sample_id" + i, point.sampleId())
                        .bind("service_name" + i, point.serviceName())
                        .bind("service_instance_id" + i, point.serviceInstanceId())
                        .bind("agent_id" + i, point.agentId())
                        .bind("metric_name" + i, point.metricName())
                        .bind("unit" + i, point.unit())
                        .bind("timestamp" + i, formatMillis(point.timestamp()))
                        .bind("value" + i, point.value())
                        .bind("attributes" + i, point.attributes());
            }

            return Flux.from(statement.execute()).flatMap(Result::getRowsUpdated).reduce(Long::sum);
        });
    }

    @Override
    public Mono<List<StoredMetricPoint>> find(
            @NonNull String workspaceId,
            @NonNull UUID projectId,
            @NonNull String serviceInstanceId,
            @NonNull String metricName,
            @NonNull Instant from,
            @NonNull Instant to,
            int limit) {
        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(FIND)
                    .bind("workspace_id", workspaceId)
                    .bind("project_id", projectId)
                    .bind("service_instance_id", serviceInstanceId)
                    .bind("metric_name", metricName)
                    .bind("from", formatMillis(from))
                    .bind("to", formatMillis(to))
                    .bind("limit", limit);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> new StoredMetricPoint(
                            row.get("timestamp", Instant.class),
                            row.get("value", Double.class),
                            row.get("unit", String.class),
                            readAttributes(row.get("attributes", Map.class)))))
                    .collectList();
        });
    }

    @Override
    public Mono<List<SystemMetricInstance>> findInstances(
            @NonNull String workspaceId,
            @NonNull UUID projectId,
            @NonNull Instant from,
            int limit) {
        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(FIND_INSTANCES)
                    .bind("workspace_id", workspaceId)
                    .bind("project_id", projectId)
                    .bind("from", formatMillis(from))
                    .bind("limit", limit);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> new SystemMetricInstance(
                            row.get("service_instance_id", String.class),
                            row.get("service_name", String.class),
                            row.get("agent_id", String.class),
                            row.get("last_seen", Instant.class))))
                    .collectList();
        });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> readAttributes(Map<?, ?> value) {
        return value == null ? Map.of() : (Map<String, String>) value;
    }
}
