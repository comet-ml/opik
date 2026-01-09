package com.comet.opik.domain;

import com.comet.opik.api.metrics.BreakdownConfig;
import lombok.experimental.UtilityClass;

import java.util.Optional;

/**
 * Utility class for building SQL query components for metric group by.
 * Simplified to support only: tags, metadata, name, error_info.
 */
@UtilityClass
public class BreakdownQueryBuilder {

    /**
     * Get the SQL expression for extracting the group by value from traces.
     *
     * @param config The breakdown configuration
     * @return SQL expression for the group value, or empty if no grouping
     */
    public static Optional<String> getTraceGroupExpression(BreakdownConfig config) {
        if (config == null || !config.isEnabled()) {
            return Optional.empty();
        }

        return Optional.of(switch (config.field()) {
            case NONE -> throw new IllegalStateException("NONE breakdown should not reach here");
            case TAGS -> "arrayJoin(if(empty(traces.tags), ['Unknown'], traces.tags))";
            case METADATA -> "coalesce(nullIf(JSONExtractString(traces.metadata, '%s'), ''), 'Unknown')"
                    .formatted(config.metadataKey());
            case NAME -> "coalesce(nullIf(traces.name, ''), 'Unknown')";
            case ERROR_INFO -> "if(length(traces.error_info) > 0, 'Has Error', 'No Error')";
        });
    }

    /**
     * Get the SQL expression for extracting the group by value from spans.
     *
     * @param config The breakdown configuration
     * @return SQL expression for the group value, or empty if no grouping
     */
    public static Optional<String> getSpanGroupExpression(BreakdownConfig config) {
        if (config == null || !config.isEnabled()) {
            return Optional.empty();
        }

        return Optional.of(switch (config.field()) {
            case NONE -> throw new IllegalStateException("NONE breakdown should not reach here");
            case TAGS -> "arrayJoin(if(empty(s.tags), ['Unknown'], s.tags))";
            case METADATA -> "coalesce(nullIf(JSONExtractString(s.metadata, '%s'), ''), 'Unknown')"
                    .formatted(config.metadataKey());
            case NAME -> "coalesce(nullIf(s.name, ''), 'Unknown')";
            case ERROR_INFO -> "if(length(s.error_info) > 0, 'Has Error', 'No Error')";
        });
    }

    /**
     * Generate the SQL for ranking and limiting groups.
     * Always sorts by value descending and limits to top 10.
     *
     * @param groupExpression  The SQL expression for the group value
     * @param valueExpression  The SQL expression for the aggregate value
     * @return SQL CTE for top groups selection
     */
    public static String getTopGroupsCTE(String groupExpression, String valueExpression) {
        return """
                , ranked_groups AS (
                    SELECT
                        %s AS group_name,
                        %s AS total_value
                    FROM base_data
                    GROUP BY group_name
                    ORDER BY total_value DESC
                ),
                top_groups AS (
                    SELECT group_name
                    FROM ranked_groups
                    LIMIT %d
                )
                """.formatted(groupExpression, valueExpression, BreakdownConfig.LIMIT);
    }

    /**
     * Generate the SQL CASE expression for mapping groups to top groups or "Others".
     * Always includes "Others" group.
     *
     * @param groupExpression The SQL expression for the group value
     * @return SQL CASE expression
     */
    public static String getGroupMappingExpression(String groupExpression) {
        return """
                CASE
                    WHEN %s IN (SELECT group_name FROM top_groups) THEN %s
                    ELSE '%s'
                END
                """.formatted(groupExpression, groupExpression, BreakdownConfig.OTHERS_GROUP_NAME);
    }
}
