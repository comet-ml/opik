package com.comet.opik.domain.evaluators;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.comet.opik.api.LogCriteria;
import com.comet.opik.api.LogItem;
import com.comet.opik.utils.template.TemplateUtils;
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
import java.util.stream.Collectors;

import static com.comet.opik.api.LogItem.LogLevel;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.infrastructure.log.tables.UserLogTableFactory.UserLogTableDAO;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.template.TemplateUtils.QueryItem;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;

@ImplementedBy(AlertEventLogsDAOImpl.class)
public interface AlertEventLogsDAO extends UserLogTableDAO {

    List<String> CUSTOM_MARKER_KEYS = List.of("event_id", "alert_id");

    static AlertEventLogsDAO create(ConnectionFactory factory) {
        return new AlertEventLogsDAOImpl(factory);
    }

    Flux<LogItem> findLogs(LogCriteria criteria);

}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AlertEventLogsDAOImpl implements AlertEventLogsDAO {

    private static final String INSERT_STATEMENT = """
            INSERT INTO alert_logs (timestamp, level, workspace_id, alert_id, message, markers)
            VALUES <items:{item |
                (
                    parseDateTime64BestEffort(:timestamp<item.index>, 9),
                    :level<item.index>,
                    :workspace_id<item.index>,
                    :alert_id<item.index>,
                    :message<item.index>,
                    mapFromArrays(:marker_keys<item.index>, :marker_values<item.index>)
                )
                <if(item.hasNext)>,<endif>
            }>
            ;
            """;

    public static final String FIND_ALL = """
            SELECT * FROM alert_logs
            WHERE workspace_id = :workspace_id
            <if(level)> AND level = :level <endif>
            <if(items)>
                <items:{item |
                    AND markers[:marker_keys<item.index>] = :marker_values<item.index>
                }>
            <endif>
            ORDER BY timestamp DESC
            <if(limit)> LIMIT :limit <endif><if(offset)> OFFSET :offset <endif>
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    public Flux<LogItem> findLogs(@NonNull LogCriteria criteria) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {

                    log.info("Finding alert logs with criteria: {}", criteria);

                    var template = TemplateUtils.newST(FIND_ALL);

                    bindTemplateParameters(criteria, template);

                    Statement statement = connection.createStatement(template.render());

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
                .ruleId(null) // Alert logs don't have rule IDs
                .message(row.get("message", String.class))
                .markers(getStringStringMap(row.get("markers", Map.class)))
                .build();
    }

    /**
     * Safely converts a Map<?, ?> to a Map<String, String>, or returns null if input is null.
     * Throws IllegalArgumentException if any key or value is not convertible to String.
     */
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
        // Always add level to template, even if null, so the template condition works
        Optional.ofNullable(criteria.level()).ifPresent(level -> template.add("level", level));
        Optional.ofNullable(criteria.size()).ifPresent(limit -> template.add("limit", limit));

        // Only calculate offset if both page and size are present
        if (criteria.page() != null && criteria.size() != null) {
            template.add("offset", (criteria.page() - 1) * criteria.size());
        }

        Optional.ofNullable(criteria.markers())
                .filter(markers -> !markers.isEmpty())
                .ifPresent(markers -> {
                    List<QueryItem> queryItems = getQueryItemPlaceHolder(markers.size());
                    template.add("items", queryItems);
                });
    }

    private void bindParameters(LogCriteria criteria, Statement statement) {
        Optional.ofNullable(criteria.level()).ifPresent(level -> statement.bind("level", level));
        Optional.ofNullable(criteria.size()).ifPresent(limit -> statement.bind("limit", limit));

        // Only bind offset if both page and size are present
        if (criteria.page() != null && criteria.size() != null) {
            statement.bind("offset", (criteria.page() - 1) * criteria.size());
        }

        Optional.ofNullable(criteria.markers())
                .filter(markers -> !markers.isEmpty())
                .ifPresent(markers -> {
                    int index = 0;
                    for (Map.Entry<String, String> entry : markers.entrySet()) {
                        statement.bind("marker_keys" + index, entry.getKey());
                        statement.bind("marker_values" + index, entry.getValue());
                        index++;
                    }
                });
    }

    @Override
    public Mono<Void> saveAll(@NonNull List<ILoggingEvent> events) {

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var template = TemplateUtils.newST(INSERT_STATEMENT);

                    List<QueryItem> queryItems = getQueryItemPlaceHolder(events.size());
                    template.add("items", queryItems);
                    Statement statement = connection.createStatement(template.render());

                    for (int i = 0; i < events.size(); i++) {

                        ILoggingEvent event = events.get(i);
                        String logLevel = event.getLevel().toString();
                        String workspaceId = Optional.ofNullable(event.getMDCPropertyMap().get("workspace_id"))
                                .orElseThrow(() -> failWithMessage("workspace_id is not set"));
                        String alertId = Optional.ofNullable(event.getMDCPropertyMap().get("alert_id"))
                                .orElseThrow(() -> failWithMessage("alert_id is not set"));

                        Map<String, String> markers = CUSTOM_MARKER_KEYS.stream()
                                .map(key -> Map.entry(key, event.getMDCPropertyMap().getOrDefault(key, "")))
                                .filter(entry -> !entry.getValue().isEmpty())
                                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

                        String[] markerKeys = markers.keySet().toArray(String[]::new);
                        String[] markerValues = markers.keySet().stream().map(markers::get).toArray(String[]::new);

                        statement
                                .bind("timestamp" + i, event.getInstant().toString())
                                .bind("level" + i, logLevel)
                                .bind("workspace_id" + i, workspaceId)
                                .bind("alert_id" + i, alertId)
                                .bind("message" + i, event.getFormattedMessage())
                                .bind("marker_keys" + i, markerKeys)
                                .bind("marker_values" + i, markerValues);
                    }

                    return statement.execute();

                })
                .then();

    }

    private IllegalStateException failWithMessage(String message) {
        log.error(message);
        return new IllegalStateException(message);
    }
}
