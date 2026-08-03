package com.comet.opik.domain;

/**
 * Shared ClickHouse query fragments for workspace-level trace feedback-score metrics. The same dedup pattern used
 * for per-project trace feedback scores is required here because online-scoring writes go to
 * {@code authored_feedback_scores} (the writer routes by author presence, see {@code FeedbackScoreDAO.BULK_INSERT_FEEDBACK_SCORE})
 * while the legacy {@code feedback_scores} table only receives rows that pre-date that writer split. Reading only the
 * legacy table — the prior behavior of the workspace metrics — would therefore miss every online-scoring score in
 * a typical deployment.
 * <p>
 * The dedup chain is: union both tables, pick the latest row per (workspace, project, entity, name, author, source),
 * then collapse to a single value per (workspace, project, entity, name), averaging across authors when multiple have
 * scored the same trace. Identical to {@code ProjectMetricsDAO.TRACE_FILTERED_PREFIX}; the project and name predicates
 * are injected via {@link #traceFeedbackScoresPrefix(String, String)} so the three workspace query constants stay in
 * sync with the dedup logic.
 * <p>
 * Per {@code .agents/skills/opik-backend/clickhouse.md}, mutable column filters must run AFTER the {@code LIMIT 1 BY}
 * dedup. {@code name} is mutable (a score can be renamed, and a single trace can carry multiple score names whose
 * versions live in different sort-key slots), so the {@code name} filter is applied in the post-dedup
 * {@code feedback_scores_final} aggregation, not on each UNION leg. {@code project_id} is immutable and stays on
 * the UNION legs so the dedup scans a smaller row set.
 */
final class WorkspaceFeedbackScoresQueries {

    private WorkspaceFeedbackScoresQueries() {
    }

    // %s placeholders: the first two apply the immutable project predicate to each UNION leg inside
    // `feedback_scores_deduped`; the third applies the mutable name predicate to the post-dedup
    // `feedback_scores_final` aggregation. Keeping `name` out of the UNION WHERE means a renamed score cannot
    // strand an older version on the latest surviving row.
    private static final String TRACE_FEEDBACK_SCORES_PREFIX_TEMPLATE = """
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
                    WHERE entity_type = 'trace'
                      AND workspace_id = :workspace_id
                      %s
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
                    WHERE entity_type = 'trace'
                      AND workspace_id = :workspace_id
                      %s
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
                WHERE 1%s
                GROUP BY workspace_id, project_id, entity_id, name
            )
            """;

    /**
     * Returns the {@code feedback_scores_deduped} and {@code feedback_scores_final} CTE preamble for a workspace
     * trace feedback-score query.
     *
     * @param projectPredicate immutable project filter (e.g. {@code project_id IN :project_ids} or
     *                         {@code project_id = :project_id}), or a StringTemplate conditional like
     *                         {@code <if(project_ids)> AND project_id IN :project_ids<endif>}. Must include the
     *                         leading space if non-empty. Applied to each UNION leg.
     * @param namePredicate    optional {@code AND name = :name} filter (must include the leading space if
     *                         non-empty), or a StringTemplate conditional. Applied AFTER dedup, in the final
     *                         aggregation, so a renamed score cannot strand an older version.
     */
    static String traceFeedbackScoresPrefix(String projectPredicate, String namePredicate) {
        return TRACE_FEEDBACK_SCORES_PREFIX_TEMPLATE.formatted(
                projectPredicate,
                projectPredicate,
                namePredicate);
    }
}
