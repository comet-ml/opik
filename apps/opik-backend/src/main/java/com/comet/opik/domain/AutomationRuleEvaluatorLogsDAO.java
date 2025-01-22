package com.comet.opik.domain;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.comet.opik.utils.TemplateUtils;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static com.comet.opik.infrastructure.log.tables.UserLogTableFactory.UserLogTableDAO;
import static com.comet.opik.utils.TemplateUtils.getQueryItemPlaceHolder;

@ImplementedBy(AutomationRuleEvaluatorLogsDAOImpl.class)
public interface AutomationRuleEvaluatorLogsDAO extends UserLogTableDAO {

    static AutomationRuleEvaluatorLogsDAO create(ConnectionFactory factory) {
        return new AutomationRuleEvaluatorLogsDAOImpl(factory);
    }

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
            WHERE workspace_id = :workspaceId
            <if(level)> AND level = :level <endif>
            <if(ruleId)> AND rule_id = :ruleId <endif>
            ORDER BY timestamp DESC
            <if(limit)> LIMIT :limit <endif><if(offset)> OFFSET :offset <endif>
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    @Override
    public Mono<Void> saveAll(@NonNull List<ILoggingEvent> events) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var template = new ST(INSERT_STATEMENT);
                    List<TemplateUtils.QueryItem> queryItems = getQueryItemPlaceHolder(events.size());

                    template.add("items", queryItems);

                    Statement statement = connection.createStatement(template.render());

                    for (int i = 0; i < events.size(); i++) {
                        ILoggingEvent event = events.get(i);

                        String logLevel = event.getLevel().toString();
                        String workspaceId = Optional.ofNullable(event.getMDCPropertyMap().get("workspace_id"))
                                .orElseThrow(() -> failWithMessage("workspace_id is not set"));
                        String traceId = Optional.ofNullable(event.getMDCPropertyMap().get("trace_id"))
                                .orElseThrow(() -> failWithMessage("trace_id is not set"));
                        String ruleId = Optional.ofNullable(event.getMDCPropertyMap().get("rule_id"))
                                .orElseThrow(() -> failWithMessage("rule_id is not set"));

                        statement
                                .bind("timestamp" + i, event.getInstant().toString())
                                .bind("level" + i, logLevel)
                                .bind("workspace_id" + i, workspaceId)
                                .bind("rule_id" + i, ruleId)
                                .bind("message" + i, event.getFormattedMessage())
                                .bind("marker_keys" + i, new String[]{"trace_id"})
                                .bind("marker_values" + i, new String[]{traceId});
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