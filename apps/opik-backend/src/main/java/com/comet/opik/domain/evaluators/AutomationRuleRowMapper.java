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
        };
    }
}
