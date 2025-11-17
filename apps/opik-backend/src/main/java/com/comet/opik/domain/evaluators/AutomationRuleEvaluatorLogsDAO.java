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
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.LogItem.LogLevel;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.infrastructure.log.tables.UserLogTableFactory.UserLogTableDAO;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.template.TemplateUtils.QueryItem;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;

@ImplementedBy(AutomationRuleEvaluatorLogsDAOImpl.class)
public interface AutomationRuleEvaluatorLogsDAO extends UserLogTableDAO {

    List<String> CUSTOM_MARKER_KEYS = List.of("trace_id", "thread_model_id");

    static AutomationRuleEvaluatorLogsDAO create(ConnectionFactory factory) {
        return new AutomationRuleEvaluatorLogsDAOImpl(factory);
    }

    Flux<LogItem> findLogs(LogCriteria criteria);

}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AutomationRuleEvaluatorLogsDAOImpl implements AutomationRuleEvaluatorLogsDAO {

    private static final String INSERT_STATEMENT = """
            INSERT INTO automation_rule_evaluator_logs (timestamp, level, workspace_id, rule_id, message, markers)
            VALUES <items:{item |
                (
                    parseDateTime64BestEffort(:timestamp<item.index>, 9),
                    :level<item.index>,
                    :workspace_id<item.index>,
                    :rule_id<item.index>,
                    :message<item.index>,
                    mapFromArrays(:marker_keys<item.index>, :marker_values<item.index>)
                )
                <if(item.hasNext)>,<endif>
            }>
            ;
            """;

    public static final String FIND_ALL = """
            SELECT * FROM automation_rule_evaluator_logs
            WHERE workspace_id = :workspace_id
            <if(level)> AND level = :level <endif>
            <if(ruleId)> AND rule_id = :rule_id <endif>
            ORDER BY timestamp DESC
            <if(limit)> LIMIT :limit <endif><if(offset)> OFFSET :offset <endif>
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    public Flux<LogItem> findLogs(@NonNull LogCriteria criteria) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {

                    log.info("Finding logs with criteria: {}", criteria);

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
                .ruleId(row.get("rule_id", UUID.class))
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
        Optional.ofNullable(criteria.level()).ifPresent(level -> template.add("level", level));
        Optional.ofNullable(criteria.entityId()).ifPresent(ruleId -> template.add("ruleId", ruleId));
        Optional.ofNullable(criteria.size()).ifPresent(limit -> template.add("limit", limit));
        // Only add offset if both page and size are present
        if (criteria.page() != null && criteria.size() != null) {
            template.add("offset", (criteria.page() - 1) * criteria.size());
        }
    }

    private void bindParameters(LogCriteria criteria, Statement statement) {
        Optional.ofNullable(criteria.level()).ifPresent(level -> statement.bind("level", level));
        Optional.ofNullable(criteria.entityId()).ifPresent(ruleId -> statement.bind("rule_id", ruleId));
        Optional.ofNullable(criteria.size()).ifPresent(limit -> statement.bind("limit", limit));
        // Only bind offset if both page and size are present
        if (criteria.page() != null && criteria.size() != null) {
            statement.bind("offset", (criteria.page() - 1) * criteria.size());
        }
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
                        String ruleId = Optional.ofNullable(event.getMDCPropertyMap().get("rule_id"))
                                .orElseThrow(() -> failWithMessage("rule_id is not set"));

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
                                .bind("rule_id" + i, ruleId)
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
