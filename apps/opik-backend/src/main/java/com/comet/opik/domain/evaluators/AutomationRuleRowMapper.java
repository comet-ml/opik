package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRule;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

public class AutomationRuleRowMapper implements RowMapper<AutomationRuleModel> {

    @Override
    public AutomationRuleModel map(ResultSet rs, StatementContext ctx) throws SQLException {

        var action = AutomationRule.AutomationRuleAction.fromString(rs.getString("action"));

        return switch (action) {
            case EVALUATOR -> ctx.findMapperFor(AutomationRuleEvaluatorModel.class)
                    .orElseThrow(() -> new IllegalStateException(
                            "No mapper found for Automation Rule Action type: %s".formatted(action)))
                    .map(rs, ctx);
            // Queue automations are read through their queue, by a query that names the action, so one
            // reaching this mapper means a caller asked for rules generically and would get a row it
            // cannot render. Failing here is louder than returning something half-mapped.
            case ANNOTATION_QUEUE_ROUTER -> throw new IllegalStateException(
                    "Annotation queue automation rules are not served through the automation rules API");
        };
    }
}
