package com.comet.opik.domain;

import com.comet.opik.api.LogCriteria;
import com.comet.opik.api.LogItem;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.LogItem.LogLevel;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.TemplateUtils.QueryItem;
import static com.comet.opik.utils.TemplateUtils.getQueryItemPlaceHolder;

@ImplementedBy(OptimizationStudioLogsDAOImpl.class)
public interface OptimizationStudioLogsDAO {

    Mono<Void> insertLogs(String workspaceId, UUID runId, List<LogItem> logs);

    Flux<LogItem> findLogs(String workspaceId, LogCriteria criteria);

}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class OptimizationStudioLogsDAOImpl implements OptimizationStudioLogsDAO {

    private static final String INSERT_STATEMENT = """
            INSERT INTO optimization_studio_logs (timestamp, level, workspace_id, run_id, message, markers)
            VALUES <items:{item |
                (
                    parseDateTime64BestEffort(:timestamp<item.index>, 9),
                    :level<item.index>,
                    :workspace_id<item.index>,
                    :run_id<item.index>,
                    :message<item.index>,
                    mapFromArrays(:marker_keys<item.index>, :marker_values<item.index>)
                )
                <if(item.hasNext)>,<endif>
            }>
            ;
            """;

    private static final String FIND_LOGS = """
            SELECT * FROM optimization_studio_logs
            WHERE workspace_id = :workspace_id
            <if(runId)> AND run_id = :run_id <endif>
            <if(level)> AND level = :level <endif>
            ORDER BY timestamp ASC
            <if(limit)> LIMIT :limit <endif><if(offset)> OFFSET :offset <endif>
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    @Override
    public Mono<Void> insertLogs(@NonNull String workspaceId, @NonNull UUID runId, @NonNull List<LogItem> logs) {
        if (logs.isEmpty()) {
            return Mono.empty();
        }

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var template = new ST(INSERT_STATEMENT);

                    List<QueryItem> queryItems = getQueryItemPlaceHolder(logs.size());
                    template.add("items", queryItems);
                    Statement statement = connection.createStatement(template.render());

                    for (int i = 0; i < logs.size(); i++) {
                        LogItem logItem = logs.get(i);

                        // Use provided timestamp or current time
                        String timestamp = logItem.timestamp() != null
                            ? logItem.timestamp().toString()
                            : Instant.now().toString();

                        // Default to INFO level if not specified
                        String level = logItem.level() != null
                            ? logItem.level().name()
                            : LogLevel.INFO.name();

                        // Get markers or use empty map
                        Map<String, String> markers = logItem.markers() != null
                            ? logItem.markers()
                            : Map.of();

                        String[] markerKeys = markers.keySet().toArray(String[]::new);
                        String[] markerValues = markers.keySet().stream()
                                .map(markers::get)
                                .toArray(String[]::new);

                        statement
                                .bind("timestamp" + i, timestamp)
                                .bind("level" + i, level)
                                .bind("workspace_id" + i, workspaceId)
                                .bind("run_id" + i, runId.toString())
                                .bind("message" + i, logItem.message())
                                .bind("marker_keys" + i, markerKeys)
                                .bind("marker_values" + i, markerValues);
                    }

                    return statement.execute();
                })
                .then();
    }

    @Override
    public Flux<LogItem> findLogs(@NonNull String workspaceId, @NonNull LogCriteria criteria) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    log.info("Finding logs with criteria: {}", criteria);

                    var template = new ST(FIND_LOGS);
                    bindTemplateParameters(criteria, template);

                    Statement statement = connection.createStatement(template.render());
                    statement.bind("workspace_id", workspaceId);
                    bindParameters(criteria, statement);

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, rowMetadata) -> mapRow(row)));
    }

    private LogItem mapRow(Row row) {
        return LogItem.builder()
                .timestamp(row.get("timestamp", Instant.class))
                .level(LogLevel.valueOf(row.get("level", String.class)))
                .workspaceId(row.get("workspace_id", String.class))
                .ruleId(row.get("run_id", UUID.class))
                .message(row.get("message", String.class))
                .markers(getStringStringMap(row.get("markers", Map.class)))
                .build();
    }

    private Map<String, String> getStringStringMap(Object mapObj) {
        if (mapObj == null) {
            return null;
        }
        if (!(mapObj instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("Expected a Map for markers, got: " + mapObj.getClass());
        }
        Map<?, ?> rawMap = (Map<?, ?>) mapObj;
        return rawMap.entrySet().stream()
                .collect(Collectors.toMap(
                        e -> String.valueOf(e.getKey()),
                        e -> String.valueOf(e.getValue())));
    }

    private void bindTemplateParameters(LogCriteria criteria, ST template) {
        Optional.ofNullable(criteria.entityId()).ifPresent(runId -> template.add("runId", runId));
        Optional.ofNullable(criteria.level()).ifPresent(level -> template.add("level", level));
        Optional.ofNullable(criteria.size()).ifPresent(limit -> template.add("limit", limit));
        if (criteria.page() != null && criteria.size() != null) {
            template.add("offset", (criteria.page() - 1) * criteria.size());
        }
    }

    private void bindParameters(LogCriteria criteria, Statement statement) {
        Optional.ofNullable(criteria.entityId()).ifPresent(runId -> statement.bind("run_id", runId));
        Optional.ofNullable(criteria.level()).ifPresent(level -> statement.bind("level", level.name()));
        Optional.ofNullable(criteria.size()).ifPresent(limit -> statement.bind("limit", limit));
        if (criteria.page() != null && criteria.size() != null) {
            statement.bind("offset", (criteria.page() - 1) * criteria.size());
        }
    }
}
