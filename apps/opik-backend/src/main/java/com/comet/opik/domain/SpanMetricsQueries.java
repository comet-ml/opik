package com.comet.opik.domain;

/**
 * Shared ClickHouse query fragments for span-based metrics. The span-filtering CTE (feedback-score dedup + span
 * filters) is identical for per-project ({@link ProjectMetricsDAO}) and workspace-level ({@link WorkspaceMetricsDAO})
 * aggregation; the only difference is the project predicate, which is injected via {@link #spanFilteredPrefix(String)}
 * so both DAOs stay in sync when the CTE changes.
 */
final class SpanMetricsQueries {

    private SpanMetricsQueries() {
    }

    // %s slots are the project predicate: "project_id = :project_id" (single project) or
    // "project_id IN :project_ids" (a resolved set of projects). workspace_id is always bound separately.
    private static final String SPAN_FILTERED_PREFIX_TEMPLATE = """
            WITH feedback_scores_deduped AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author,
                       source_queue_id
                FROM (
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           value,
                           last_updated_at,
                           last_updated_by AS author,
                           CAST('' AS FixedString(36)) AS source_queue_id
                    FROM feedback_scores
                    WHERE entity_type = 'span'
                      AND workspace_id = :workspace_id
                      AND %s
                      <if(uuid_from_time)> AND entity_id >= :uuid_from_time<endif>
                      <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time<endif>
                    UNION ALL
                    SELECT workspace_id,
                           project_id,
                           entity_id,
                           name,
                           value,
                           last_updated_at,
                           author,
                           source_queue_id
                    FROM authored_feedback_scores
                    WHERE entity_type = 'span'
                      AND workspace_id = :workspace_id
                      AND %s
                      <if(uuid_from_time)> AND entity_id >= :uuid_from_time<endif>
                      <if(uuid_to_time)> AND entity_id \\<= :uuid_to_time<endif>
                )
                ORDER BY last_updated_at DESC
                LIMIT 1 BY workspace_id, project_id, entity_id, name, author, source_queue_id
             ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value,
                    max(last_updated_at) AS last_updated_at
                FROM feedback_scores_deduped
                GROUP BY workspace_id, project_id, entity_id, name
            ),
            <if(feedback_scores_empty_filters)>
             fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM (
                    SELECT *
                    FROM feedback_scores_final
                    ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                 )
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            ),
            <endif>
            spans_filtered AS (
                SELECT
                    id,
                    UUIDv7ToDateTime(toUUID(id)) as span_time,
                    duration,
                    usage,
                    error_info,
                    total_estimated_cost
                    <if(group_expression)>,
                    project_id,
                    name,
                    tags,
                    metadata,
                    model,
                    provider,
                    type
                    <endif>
                FROM (
                    SELECT
                        id,
                        duration,
                        usage,
                        error_info,
                        total_estimated_cost
                        <if(group_expression)>,
                        project_id,
                        name,
                        tags,
                        metadata,
                        model,
                        provider,
                        type
                        <endif>
                    FROM spans FINAL
                    <if(feedback_scores_empty_filters)>
                    LEFT JOIN fsc ON fsc.entity_id = spans.id
                    <endif>
                    WHERE workspace_id = :workspace_id
                    AND %s
                    <if(uuid_from_time)> AND id >= :uuid_from_time<endif>
                    <if(uuid_to_time)> AND id \\<= :uuid_to_time<endif>
                    <if(span_filters)> AND <span_filters> <endif>
                    <if(span_feedback_scores_filters)>
                    AND id in (
                        SELECT
                            entity_id
                        FROM (
                            SELECT *
                            FROM feedback_scores_final
                            ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                            LIMIT 1 BY entity_id, name
                        )
                        GROUP BY entity_id
                        HAVING <span_feedback_scores_filters>
                    )
                    <endif>
                    <if(feedback_scores_empty_filters)>
                    AND fsc.feedback_scores_count = 0
                    <endif>
                ) AS t
            )
            """;

    static String spanFilteredPrefix(String projectPredicate) {
        return SPAN_FILTERED_PREFIX_TEMPLATE.formatted(projectPredicate, projectPredicate, projectPredicate);
    }
}
