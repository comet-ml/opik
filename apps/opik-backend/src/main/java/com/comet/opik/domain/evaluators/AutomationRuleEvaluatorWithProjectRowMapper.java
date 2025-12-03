package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Row mapper that handles individual rows from the join query with automation_rule_projects.
 * Each row contains one project_id, so multiple rows with the same rule_id need to be aggregated.
 */
@RequiredArgsConstructor
public class AutomationRuleEvaluatorWithProjectRowMapper implements RowMapper<AutomationRuleEvaluatorModel<?>> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public AutomationRuleEvaluatorModel<?> map(ResultSet rs, StatementContext ctx) throws SQLException {
        UUID id = UUID.fromString(rs.getString("id"));
        String name = rs.getString("name");
        Float samplingRate = rs.getFloat("sampling_rate");
        boolean enabled = rs.getBoolean("enabled");
        String filters = rs.getString("filters");
        Instant createdAt = rs.getTimestamp("created_at").toInstant();
        String createdBy = rs.getString("created_by");
        Instant lastUpdatedAt = rs.getTimestamp("last_updated_at").toInstant();
        String lastUpdatedBy = rs.getString("last_updated_by");

        // Get project_id from current row
        Set<UUID> projectIds = new HashSet<>();
        String projectIdStr = rs.getString("project_id");
        if (projectIdStr != null && !rs.wasNull()) {
            projectIds.add(UUID.fromString(projectIdStr));
        }

        AutomationRuleEvaluatorType type = AutomationRuleEvaluatorType.fromString(rs.getString("type"));
        String codeJson = rs.getString("code");

        try {
            JsonNode codeNode = OBJECT_MAPPER.readTree(codeJson);

            return switch (type) {
                case LLM_AS_JUDGE -> LlmAsJudgeAutomationRuleEvaluatorModel.builder()
                        .id(id)
                        .projectIds(projectIds)
                        .name(name)
                        .samplingRate(samplingRate)
                        .enabled(enabled)
                        .filters(filters)
                        .code(OBJECT_MAPPER.treeToValue(codeNode,
                                LlmAsJudgeAutomationRuleEvaluatorModel.LlmAsJudgeCode.class))
                        .createdAt(createdAt)
                        .createdBy(createdBy)
                        .lastUpdatedAt(lastUpdatedAt)
                        .lastUpdatedBy(lastUpdatedBy)
                        .build();

                case USER_DEFINED_METRIC_PYTHON -> UserDefinedMetricPythonAutomationRuleEvaluatorModel.builder()
                        .id(id)
                        .projectIds(projectIds)
                        .name(name)
                        .samplingRate(samplingRate)
                        .enabled(enabled)
                        .filters(filters)
                        .code(OBJECT_MAPPER.treeToValue(codeNode,
                                UserDefinedMetricPythonAutomationRuleEvaluatorModel.UserDefinedMetricPythonCode.class))
                        .createdAt(createdAt)
                        .createdBy(createdBy)
                        .lastUpdatedAt(lastUpdatedAt)
                        .lastUpdatedBy(lastUpdatedBy)
                        .build();

                case TRACE_THREAD_LLM_AS_JUDGE -> TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel.builder()
                        .id(id)
                        .projectIds(projectIds)
                        .name(name)
                        .samplingRate(samplingRate)
                        .enabled(enabled)
                        .filters(filters)
                        .code(OBJECT_MAPPER.treeToValue(codeNode,
                                TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel.TraceThreadLlmAsJudgeCode.class))
                        .createdAt(createdAt)
                        .createdBy(createdBy)
                        .lastUpdatedAt(lastUpdatedAt)
                        .lastUpdatedBy(lastUpdatedBy)
                        .build();

                case TRACE_THREAD_USER_DEFINED_METRIC_PYTHON ->
                    TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel.builder()
                            .id(id)
                            .projectIds(projectIds)
                            .name(name)
                            .samplingRate(samplingRate)
                            .enabled(enabled)
                            .filters(filters)
                            .code(OBJECT_MAPPER.treeToValue(codeNode,
                                    TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel.TraceThreadUserDefinedMetricPythonCode.class))
                            .createdAt(createdAt)
                            .createdBy(createdBy)
                            .lastUpdatedAt(lastUpdatedAt)
                            .lastUpdatedBy(lastUpdatedBy)
                            .build();

                case SPAN_LLM_AS_JUDGE -> SpanLlmAsJudgeAutomationRuleEvaluatorModel.builder()
                        .id(id)
                        .projectIds(projectIds)
                        .name(name)
                        .samplingRate(samplingRate)
                        .enabled(enabled)
                        .filters(filters)
                        .code(OBJECT_MAPPER.treeToValue(codeNode,
                                SpanLlmAsJudgeAutomationRuleEvaluatorModel.SpanLlmAsJudgeCode.class))
                        .createdAt(createdAt)
                        .createdBy(createdBy)
                        .lastUpdatedAt(lastUpdatedAt)
                        .lastUpdatedBy(lastUpdatedBy)
                        .build();
            };
        } catch (Exception e) {
            throw new SQLException("Failed to parse automation rule evaluator code", e);
        }
    }
}
