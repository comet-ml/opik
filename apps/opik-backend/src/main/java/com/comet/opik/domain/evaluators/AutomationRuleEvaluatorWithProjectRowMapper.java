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
 * Row mapper that handles rows from the join query with automation_rule_projects.
 * Uses GROUP_CONCAT to aggregate multiple project_ids into a comma-separated string per rule.
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

        // Get project_ids from GROUP_CONCAT result (comma-separated)
        Set<UUID> projectIds = new HashSet<>();
        String projectIdsStr = rs.getString("project_ids");
        if (projectIdsStr != null && !rs.wasNull() && !projectIdsStr.trim().isEmpty()) {
            String[] ids = projectIdsStr.split(",");
            for (String idStr : ids) {
                String trimmed = idStr.trim();
                if (!trimmed.isEmpty()) {
                    projectIds.add(UUID.fromString(trimmed));
                }
            }
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
