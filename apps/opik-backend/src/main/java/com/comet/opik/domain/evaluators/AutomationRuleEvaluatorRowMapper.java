package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.evaluators.EvalTriggerScope;
import com.comet.opik.api.evaluators.ProjectReference;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.mapper.RowMapper;
import org.jdbi.v3.core.statement.StatementContext;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.SortedSet;
import java.util.UUID;

import static com.comet.opik.domain.evaluators.LlmAsJudgeAutomationRuleEvaluatorModel.LlmAsJudgeCode;
import static com.comet.opik.domain.evaluators.SpanLlmAsJudgeAutomationRuleEvaluatorModel.SpanLlmAsJudgeCode;
import static com.comet.opik.domain.evaluators.SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel.SpanUserDefinedMetricPythonCode;
import static com.comet.opik.domain.evaluators.TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel.TraceThreadLlmAsJudgeCode;
import static com.comet.opik.domain.evaluators.TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel.TraceThreadUserDefinedMetricPythonCode;
import static com.comet.opik.domain.evaluators.UserDefinedMetricPythonAutomationRuleEvaluatorModel.UserDefinedMetricPythonCode;

/**
 * Custom row mapper for AutomationRuleEvaluatorModel that handles:
 * 1. Legacy project_id fallback (for rules created before multi-project support)
 * 2. Type-specific code field parsing
 * 3. Fields that will be enriched later by Service layer (projectId, projectName, projects)
 */
@Slf4j
public class AutomationRuleEvaluatorRowMapper implements RowMapper<AutomationRuleEvaluatorModel<?>> {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Override
    public AutomationRuleEvaluatorModel<?> map(ResultSet rs, StatementContext ctx) throws SQLException {
        // Extract common fields from ResultSet
        UUID id = UUID.fromString(rs.getString("id"));
        String name = rs.getString("name");
        Float samplingRate = rs.getFloat("sampling_rate");
        boolean enabled = rs.getBoolean("enabled");
        String triggerScopeStr = rs.getString("trigger_scope");
        EvalTriggerScope triggerScope = triggerScopeStr != null ? EvalTriggerScope.fromString(triggerScopeStr) : null;
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
            projectIds.add(UUID.fromString(legacyProjectIdStr));
        }

        // These fields will be enriched by Service layer:
        // - projectId: Derived from first project (for backward compatibility)
        // - projectName: Derived from first project (for backward compatibility)
        // - projects: SortedSet of ProjectReference built from projectIds + fetched names
        UUID projectId = null;
        String projectName = null;
        SortedSet<ProjectReference> projects = null;

        // Parse type-specific code field
        AutomationRuleEvaluatorType storedType = AutomationRuleEvaluatorType.fromString(rs.getString("type"));
        String codeJson = rs.getString("code");

        try {
            JsonNode codeNode = OBJECT_MAPPER.readTree(codeJson);
            AutomationRuleEvaluatorType type = resolveType(id, storedType, codeNode);

            // Build the appropriate model type based on evaluator type
            return switch (type) {
                case LLM_AS_JUDGE -> LlmAsJudgeAutomationRuleEvaluatorModel.builder()
                        .id(id)
                        .projectId(projectId)
                        .projectName(projectName)
                        .projectIds(projectIds)
                        .projects(projects)
                        .name(name)
                        .samplingRate(samplingRate)
                        .enabled(enabled)
                        .triggerScope(triggerScope)
                        .filters(filters)
                        .code(OBJECT_MAPPER.treeToValue(codeNode, LlmAsJudgeCode.class))
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
                        .projects(projects)
                        .name(name)
                        .samplingRate(samplingRate)
                        .enabled(enabled)
                        .triggerScope(triggerScope)
                        .filters(filters)
                        .code(OBJECT_MAPPER.treeToValue(codeNode, UserDefinedMetricPythonCode.class))
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
                        .projects(projects)
                        .name(name)
                        .samplingRate(samplingRate)
                        .enabled(enabled)
                        .triggerScope(triggerScope)
                        .filters(filters)
                        .code(OBJECT_MAPPER.treeToValue(codeNode, TraceThreadLlmAsJudgeCode.class))
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
                            .projects(projects)
                            .name(name)
                            .samplingRate(samplingRate)
                            .enabled(enabled)
                            .triggerScope(triggerScope)
                            .filters(filters)
                            .code(OBJECT_MAPPER.treeToValue(codeNode, TraceThreadUserDefinedMetricPythonCode.class))
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
                        .projects(projects)
                        .name(name)
                        .samplingRate(samplingRate)
                        .enabled(enabled)
                        .triggerScope(triggerScope)
                        .filters(filters)
                        .code(OBJECT_MAPPER.treeToValue(codeNode, SpanLlmAsJudgeCode.class))
                        .createdAt(createdAt)
                        .createdBy(createdBy)
                        .lastUpdatedAt(lastUpdatedAt)
                        .lastUpdatedBy(lastUpdatedBy)
                        .build();

                case SPAN_USER_DEFINED_METRIC_PYTHON ->
                    SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel.builder()
                            .id(id)
                            .projectId(projectId)
                            .projectName(projectName)
                            .projectIds(projectIds)
                            .projects(projects)
                            .name(name)
                            .samplingRate(samplingRate)
                            .enabled(enabled)
                            .triggerScope(triggerScope)
                            .filters(filters)
                            .code(OBJECT_MAPPER.treeToValue(codeNode, SpanUserDefinedMetricPythonCode.class))
                            .createdAt(createdAt)
                            .createdBy(createdBy)
                            .lastUpdatedAt(lastUpdatedAt)
                            .lastUpdatedBy(lastUpdatedBy)
                            .build();
            };

        } catch (JsonProcessingException e) {
            throw new SQLException("Failed to parse automation rule evaluator code", e);
        }
    }

    /**
     * Updates used to accept a type change while writing only the code, leaving rows whose code belongs to the other
     * evaluator kind of the same scope. Judge code always carries a model and Python code never does, so the code
     * decides between the two, and the rule reads as the type its code was written for.
     */
    private static AutomationRuleEvaluatorType resolveType(
            UUID id, AutomationRuleEvaluatorType storedType, JsonNode codeNode) {
        if (isLlmAsJudge(storedType) == codeNode.has("model")) {
            return storedType;
        }
        AutomationRuleEvaluatorType resolvedType = sibling(storedType);
        log.warn("Evaluator code doesn't match its stored type, reading it as the code's type, id '{}', "
                + "stored type '{}', resolved type '{}'", id, storedType.getType(), resolvedType.getType());
        return resolvedType;
    }

    private static boolean isLlmAsJudge(AutomationRuleEvaluatorType type) {
        return switch (type) {
            case LLM_AS_JUDGE, TRACE_THREAD_LLM_AS_JUDGE, SPAN_LLM_AS_JUDGE -> true;
            case USER_DEFINED_METRIC_PYTHON, TRACE_THREAD_USER_DEFINED_METRIC_PYTHON,
                    SPAN_USER_DEFINED_METRIC_PYTHON ->
                false;
        };
    }

    private static AutomationRuleEvaluatorType sibling(AutomationRuleEvaluatorType type) {
        return switch (type) {
            case LLM_AS_JUDGE -> AutomationRuleEvaluatorType.USER_DEFINED_METRIC_PYTHON;
            case USER_DEFINED_METRIC_PYTHON -> AutomationRuleEvaluatorType.LLM_AS_JUDGE;
            case TRACE_THREAD_LLM_AS_JUDGE -> AutomationRuleEvaluatorType.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON;
            case TRACE_THREAD_USER_DEFINED_METRIC_PYTHON -> AutomationRuleEvaluatorType.TRACE_THREAD_LLM_AS_JUDGE;
            case SPAN_LLM_AS_JUDGE -> AutomationRuleEvaluatorType.SPAN_USER_DEFINED_METRIC_PYTHON;
            case SPAN_USER_DEFINED_METRIC_PYTHON -> AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE;
        };
    }
}
