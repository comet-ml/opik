package com.comet.opik.domain;

/**
 * Shared ClickHouse query fragments for span-based metrics. The span-filtering CTE (feedback-score dedup + span
 * filters) is identical for per-project ({@link ProjectMetricsDAO}) and workspace-level ({@link WorkspaceMetricsDAO})
 * aggregation; the only difference is the project predicate, which each DAO selects by setting the matching
 * StringTemplate flag, so both DAOs stay in sync when the CTE changes.
 * <p>
 * Each {@code id}-range bound on the {@code spans} scan carries a parallel {@code toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1))} bound: a strict
 * consequence of the id-range that scans the same rows but engages weekly-partition pruning once {@code spans} is
 * partitioned, which the planner can't infer through {@code UUIDv7ToDateTime}.
 */
final class SpanMetricsQueries {

    private SpanMetricsQueries() {
    }

    // The project predicate is bound, not spliced: both callers bind `project_ids`, a per-project set of one
    // ({@link ProjectMetricsDAO}) or a resolved set of many ({@link WorkspaceMetricsDAO}). The `IN` form covers
    // both, so the query text stays a constant with no conditional. workspace_id is always bound separately.
    static final String SPAN_FILTERED_PREFIX = """
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
                      AND project_id IN :project_ids
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
                      AND project_id IN :project_ids
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
                    AND project_id IN :project_ids
                    <if(uuid_from_time)> AND id >= :uuid_from_time
                    AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1))) >= toMonday(UUIDv7ToDateTime(toUUID(:uuid_from_time), 'UTC'))<endif>
                    <if(uuid_to_time)> AND id \\<= :uuid_to_time
                    AND (toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1))) \\<= toMonday(UUIDv7ToDateTime(toUUID(:uuid_to_time), 'UTC'))<endif>
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

    // Distinct span token-usage key names. The project predicate is bound as in SPAN_FILTERED_PREFIX above: both
    // callers bind `project_ids`, a set of one ({@link ProjectMetricsDAO}) or many ({@link WorkspaceMetricsDAO});
    // workspace_id is always bound separately. Shared so the two callers can't drift.
    static final String TOKEN_USAGE_NAMES = """
            SELECT DISTINCT name
            FROM (
                SELECT usage
                FROM spans final
                WHERE workspace_id = :workspace_id
                AND project_id IN :project_ids
            )
            ARRAY JOIN
                mapKeys(usage) AS name,
                mapValues(usage) AS value
            WHERE value > 0
            SETTINGS log_comment = '<log_comment>';
            """;
}
