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
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.api.LogItem.LogLevel;
import static com.comet.opik.api.LogItem.LogPage;

@ImplementedBy(AutomationRuleEvaluatorLogsDAOImpl.class)
public interface AutomationRuleEvaluatorLogsDAO {

    Mono<LogPage> findLogs(LogCriteria criteria);

}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AutomationRuleEvaluatorLogsDAOImpl implements AutomationRuleEvaluatorLogsDAO {

    public static final String FIND_ALL = """
            SELECT * FROM automation_rule_evaluator_logs
            WHERE workspace_id = :workspaceId
            <if(level)> AND level = :level <endif>
            <if(ruleId)> AND rule_id = :ruleId <endif>
            ORDER BY timestamp DESC
            <if(limit)> LIMIT :limit <endif><if(offset)> OFFSET :offset <endif>
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    public Mono<LogPage> findLogs(@NonNull LogCriteria criteria) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    log.info("Finding logs with criteria: {}", criteria);

                    var template = new ST(FIND_ALL);

                    bindTemplateParameters(criteria, template);

                    Statement statement = connection.createStatement(template.render());

                    bindParameters(criteria, statement);

                    return statement.execute();
                })
                .flatMap(result -> result.map((row, rowMetadata) -> mapRow(row)))
                .collectList()
                .map(this::mapPage);
    }

    private LogPage mapPage(List<LogItem> logs) {
        return LogPage.builder()
                .content(logs)
                .page(1)
                .total(logs.size())
                .size(logs.size())
                .build();
    }

    private LogItem mapRow(Row row) {
        return LogItem.builder()
                .timestamp(row.get("timestamp", Instant.class))
                .level(LogLevel.valueOf(row.get("level", String.class)))
                .workspaceId(row.get("workspace_id", String.class))
                .ruleId(row.get("rule_id", UUID.class))
                .message(row.get("message", String.class))
                .markers(row.get("markers", Map.class))
                .build();
    }

    private void bindTemplateParameters(LogCriteria criteria, ST template) {
        Optional.ofNullable(criteria.level()).ifPresent(level -> template.add("level", level));
        Optional.ofNullable(criteria.entityId()).ifPresent(ruleId -> template.add("ruleId", ruleId));
        Optional.ofNullable(criteria.size()).ifPresent(limit -> template.add("limit", limit));
    }

    private void bindParameters(LogCriteria criteria, Statement statement) {
        statement.bind("workspaceId", criteria.workspaceId());
        Optional.ofNullable(criteria.level()).ifPresent(level -> statement.bind("level", level));
        Optional.ofNullable(criteria.entityId()).ifPresent(ruleId -> statement.bind("ruleId", ruleId));
        Optional.ofNullable(criteria.size()).ifPresent(limit -> statement.bind("limit", limit));
    }

}