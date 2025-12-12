package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Row mapper for automation rule evaluators that delegates to type-specific mappers.
 * Uses the Functional Factory Registry pattern to eliminate switch statements.
 * The AutomationRuleEvaluatorType enum holds a reference to the corresponding model class.
 */
public class AutomationRuleEvaluatorRowMapper implements RowMapper<AutomationRuleEvaluatorModel<?>> {

    @Override
    public AutomationRuleEvaluatorModel<?> map(ResultSet rs, StatementContext ctx) throws SQLException {
        var type = AutomationRuleEvaluatorType.fromString(rs.getString("type"));

        // 🎯 No switch! The type knows its model class via method reference
        return ctx.findMapperFor(type.getModelClass())
                .orElseThrow(() -> new IllegalStateException(
                        "No mapper found for Automation Rule Evaluator type: %s".formatted(type)))
                .map(rs, ctx);
    }
}
