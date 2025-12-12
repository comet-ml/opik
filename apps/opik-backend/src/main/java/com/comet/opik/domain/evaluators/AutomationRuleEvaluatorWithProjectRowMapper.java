package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
 * Each row contains one project_id, multiple rows with the same rule_id are aggregated in Java.
 */
@Slf4j
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

        // Legacy fallback: If rule was created before multi-project support,
        // it will have project_id set but no entries in automation_rule_projects junction table.
        // We initialize projectIds with the legacy value as a fallback.
        // The service layer will replace this with junction table data if available.
        Set<UUID> projectIds = new HashSet<>();

        String legacyProjectIdStr = rs.getString("legacy_project_id");
        if (legacyProjectIdStr != null) {
            UUID legacyProjectId = UUID.fromString(legacyProjectIdStr);
            projectIds.add(legacyProjectId); // Initialize with legacy value as fallback
            log.debug("Initialized rule '{}' with legacy project_id '{}'", id, legacyProjectId);
        }

        // Both projectId and projectName will be enriched by Service layer
        // projectId: Derived from first element of projectIds (for backward compatibility)
        // projectName: Fetched from projects table based on projectId
        UUID projectId = null;
        String projectName = null;

        AutomationRuleEvaluatorType type = AutomationRuleEvaluatorType.fromString(rs.getString("type"));
        String codeJson = rs.getString("code");

        try {
            JsonNode codeNode = OBJECT_MAPPER.readTree(codeJson);

            return switch (type) {
                case LLM_AS_JUDGE -> LlmAsJudgeAutomationRuleEvaluatorModel.builder()
                        .id(id)
                        .projectId(projectId)
                        .projectName(projectName)
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
                        .projectId(projectId)
                        .projectName(projectName)
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
                        .projectId(projectId)
                        .projectName(projectName)
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
                            .projectId(projectId)
                            .projectName(projectName)
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
                        .projectId(projectId)
                        .projectName(projectName)
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

                case SPAN_USER_DEFINED_METRIC_PYTHON -> SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel
                        .builder()
                        .id(id)
                        .projectId(projectId)
                        .projectName(projectName)
                        .projectIds(projectIds)
                        .name(name)
                        .samplingRate(samplingRate)
                        .enabled(enabled)
                        .filters(filters)
                        .code(OBJECT_MAPPER.treeToValue(codeNode,
                                SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel.SpanUserDefinedMetricPythonCode.class))
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
