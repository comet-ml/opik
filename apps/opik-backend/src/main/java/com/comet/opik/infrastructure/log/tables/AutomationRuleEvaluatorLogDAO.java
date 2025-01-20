package com.comet.opik.infrastructure.log.tables;

import ch.qos.logback.classic.spi.ILoggingEvent;
import com.comet.opik.utils.TemplateUtils;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Statement;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;

import static com.comet.opik.infrastructure.log.tables.UserLogTableFactory.UserLogTableDAO;
import static com.comet.opik.utils.TemplateUtils.getQueryItemPlaceHolder;

@RequiredArgsConstructor
@Slf4j
class AutomationRuleEvaluatorLogDAO implements UserLogTableDAO {

    private final ConnectionFactory factory;

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

    @Override
    public Mono<Void> saveAll(List<ILoggingEvent> events) {
        return Mono.from(factory.create())
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
