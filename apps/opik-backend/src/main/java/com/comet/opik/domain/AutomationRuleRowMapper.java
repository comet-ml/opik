package com.comet.opik.domain;

import com.comet.opik.api.AutomationRule;
import com.comet.opik.api.AutomationRuleEvaluator;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

import static com.comet.opik.domain.FeedbackDefinitionModel.FeedbackType;

public class AutomationRuleRowMapper implements RowMapper<AutomationRule<?>> {

    @Override
    public AutomationRule<?> map(ResultSet rs, StatementContext ctx) throws SQLException {

        var action = AutomationRule.AutomationRuleAction.fromString(rs.getString("action"));

        return switch (action) {
            case EVALUATOR -> ctx.findMapperFor(AutomationRuleEvaluator.class)
                    .orElseThrow(() -> new IllegalStateException(
                            "No mapper found for Automation Rule Action type: %s".formatted(action)))
                    .map(rs, ctx);
        };
    }
}
