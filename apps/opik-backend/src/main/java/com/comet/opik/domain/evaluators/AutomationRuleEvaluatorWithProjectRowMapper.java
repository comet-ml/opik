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

    /**
     * Common fields extracted from ResultSet, passed to all factory methods.
     * Reduces parameter duplication from 14 parameters to a single object.
     * Made public to be accessible by AutomationRuleEvaluatorType in API layer.
     */
    public record CommonFields(
            UUID id,
            UUID projectId,
            String projectName,
            Set<UUID> projectIds,
            String name,
            Float samplingRate,
            boolean enabled,
            String filters,
            Instant createdAt,
            String createdBy,
            Instant lastUpdatedAt,
            String lastUpdatedBy) {
    }

    /**
     * Functional interface for building a model with common fields + type-specific code.
     * This allows each model to use its own builder while sharing the field assignment logic.
     */
    @FunctionalInterface
    public interface ModelBuilder<T, M extends AutomationRuleEvaluatorModel<T>> {
        M build(CommonFields common, T code);
    }

    /**
     * Generic helper that applies common fields to any model builder.
     * Eliminates duplication while keeping type safety and avoiding reflection.
     *
     * @param common Common fields from ResultSet
     * @param codeParser Function to parse JsonNode into type-specific code object
     * @param codeNode JSON representation of code field
     * @param modelBuilder Function that builds the final model from common fields and code
     * @return Fully constructed model
     */
    public static <T, M extends AutomationRuleEvaluatorModel<T>> M buildModel(
            CommonFields common,
            java.util.function.Function<JsonNode, T> codeParser,
            JsonNode codeNode,
            ModelBuilder<T, M> modelBuilder) throws com.fasterxml.jackson.core.JsonProcessingException {

        T code = codeParser.apply(codeNode);
        return modelBuilder.build(common, code);
    }

    @Override
    public AutomationRuleEvaluatorModel<?> map(ResultSet rs, StatementContext ctx) throws SQLException {
        // Extract common fields once
        CommonFields common = extractCommonFields(rs);

        // Extract type-specific fields
        AutomationRuleEvaluatorType type = AutomationRuleEvaluatorType.fromString(rs.getString("type"));
        String codeJson = rs.getString("code");

        try {
            JsonNode codeNode = OBJECT_MAPPER.readTree(codeJson);

            // Delegate to the type's factory via method reference (Strategy pattern)
            // No switch statement needed - each type knows how to construct itself
            // Similar to RedisStreamCodec pattern used elsewhere in the codebase
            return type.fromRowMapper(common, codeNode, OBJECT_MAPPER);

        } catch (Exception e) {
            throw new SQLException("Failed to parse automation rule evaluator code", e);
        }
    }

    /**
     * Extracts common fields from ResultSet into a single object.
     * This method centralizes the field extraction logic and handles legacy fallback.
     */
    private CommonFields extractCommonFields(ResultSet rs) throws SQLException {
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

        return new CommonFields(id, projectId, projectName, projectIds, name, samplingRate,
                enabled, filters, createdAt, createdBy, lastUpdatedAt, lastUpdatedBy);
    }
}
