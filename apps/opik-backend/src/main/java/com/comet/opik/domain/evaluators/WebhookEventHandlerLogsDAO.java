package com.comet.opik.domain.evaluators;

import ch.qos.logback.classic.spi.ILoggingEvent;
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
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import static com.comet.opik.api.LogItem.LogLevel;
import static com.comet.opik.api.LogItem.LogPage;
import static com.comet.opik.infrastructure.log.tables.UserLogTableFactory.UserLogTableDAO;
import static com.comet.opik.utils.TemplateUtils.QueryItem;
import static com.comet.opik.utils.TemplateUtils.getQueryItemPlaceHolder;

@ImplementedBy(WebhookEventHandlerLogsDAOImpl.class)
public interface WebhookEventHandlerLogsDAO extends UserLogTableDAO {

    List<String> CUSTOM_MARKER_KEYS = List.of("event_id", "alert_id");

    static WebhookEventHandlerLogsDAO create(ConnectionFactory factory) {
        return new WebhookEventHandlerLogsDAOImpl(factory);
    }

    Mono<LogPage> findLogs(LogCriteria criteria);

}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class WebhookEventHandlerLogsDAOImpl implements WebhookEventHandlerLogsDAO {

    private static final String INSERT_STATEMENT = """
            INSERT INTO logs (timestamp, level, workspace_id, message, markers)
            VALUES <items:{item |
                (
                    parseDateTime64BestEffort(:timestamp<item.index>, 9),
                    :level<item.index>,
                    :workspace_id<item.index>,
                    :message<item.index>,
                    mapFromArrays(:marker_keys<item.index>, :marker_values<item.index>)
                )
                <if(item.hasNext)>,<endif>
            }>
            ;
            """;

    public static final String FIND_ALL = """
            SELECT * FROM logs
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

    public Mono<LogPage> findLogs(@NonNull LogCriteria criteria) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {

                    log.info("Finding webhook logs with criteria: {}", criteria);

                    var template = new ST(FIND_ALL);

                    bindTemplateParameters(criteria, template);

                    Statement statement = connection.createStatement(template.render());

                    bindParameters(criteria, statement);

                    return statement.execute();

                })
                .flatMap(result -> result.map((row, rowMetadata) -> mapRow(row)))
                .collectList()
                .map(logs -> mapPage(logs, criteria.page()));
    }

    private LogPage mapPage(List<LogItem> logs, Integer page) {
        return LogPage.builder()
                .content(logs)
                .page(page)
                .total(logs.size())
                .size(logs.size())
                .build();
    }

    private LogItem mapRow(Row row) {
        return LogItem.builder()
                .timestamp(row.get("timestamp", Instant.class))
                .level(LogLevel.valueOf(row.get("level", String.class)))
                .workspaceId(row.get("workspace_id", String.class))
                .ruleId(null) // Webhook logs don't have rule IDs
                .message(row.get("message", String.class))
                .markers((Map<String, String>) row.get("markers", Map.class)) // Cast required for ClickHouse Map type
                .build();
    }

    private void bindTemplateParameters(LogCriteria criteria, ST template) {
        // Always add level to template, even if null, so the template condition works
        template.add("level", criteria.level());
        Optional.of(criteria.size()).ifPresent(limit -> template.add("limit", limit));
        Optional.of(criteria.page()).ifPresent(page -> template.add("offset", (page - 1) * criteria.size()));

        Optional.ofNullable(criteria.markers())
                .filter(markers -> !markers.isEmpty())
                .ifPresent(markers -> {
                    List<QueryItem> queryItems = getQueryItemPlaceHolder(markers.size());
                    template.add("items", queryItems);
                });
    }

    private void bindParameters(LogCriteria criteria, Statement statement) {
        statement.bind("workspace_id", criteria.workspaceId());
        Optional.ofNullable(criteria.level()).ifPresent(level -> statement.bind("level", level));
        Optional.of(criteria.size()).ifPresent(limit -> statement.bind("limit", limit));
        Optional.of(criteria.page()).ifPresent(page -> statement.bind("offset", (page - 1) * criteria.size()));

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
                    var template = new ST(INSERT_STATEMENT);

                    List<QueryItem> queryItems = getQueryItemPlaceHolder(events.size());
                    template.add("items", queryItems);
                    Statement statement = connection.createStatement(template.render());

                    for (int i = 0; i < events.size(); i++) {

                        ILoggingEvent event = events.get(i);
                        String logLevel = event.getLevel().toString();
                        String workspaceId = Optional.ofNullable(event.getMDCPropertyMap().get("workspace_id"))
                                .orElseThrow(() -> failWithMessage("workspace_id is not set"));

                        Map<String, String> makers = CUSTOM_MARKER_KEYS.stream()
                                .map(key -> Map.entry(key, event.getMDCPropertyMap().getOrDefault(key, "")))
                                .filter(entry -> !entry.getValue().isEmpty())
                                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

                        String[] markerKeys = makers.keySet().toArray(String[]::new);
                        String[] markerValues = makers.keySet().stream().map(makers::get).toArray(String[]::new);

                        statement
                                .bind("timestamp" + i, event.getInstant().toString())
                                .bind("level" + i, logLevel)
                                .bind("workspace_id" + i, workspaceId)
                                .bind("message" + i, event.getFormattedMessage())
                                .bind("marker_keys" + i, markerKeys)
                                .bind("marker_values" + i, markerValues);
                    }

                    return statement.execute();

                })
                .collectList()
                .then();

    }

    private IllegalStateException failWithMessage(String message) {
        log.error(message);
        return new IllegalStateException(message);
    }
}
