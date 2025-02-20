package com.comet.opik.domain;

import com.comet.opik.api.AutomationRuleEvaluatorType;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;

public class AutomationRuleEvaluatorRowMapper implements RowMapper<AutomationRuleEvaluatorModel<?>> {

    @Override
    public AutomationRuleEvaluatorModel<?> map(ResultSet rs, StatementContext ctx) throws SQLException {
        var type = AutomationRuleEvaluatorType.fromString(rs.getString("type"));
        var mapperClass = switch (type) {
            case LLM_AS_JUDGE -> LlmAsJudgeAutomationRuleEvaluatorModel.class;
            case USER_DEFINED_METRIC_PYTHON -> UserDefinedMetricPythonAutomationRuleEvaluatorModel.class;
        };
        return ctx.findMapperFor(mapperClass)
                .orElseThrow(() -> new IllegalStateException(
                        "No mapper found for Automation Rule Evaluator type: %s".formatted(type)))
                .map(rs, ctx);
    }
}
