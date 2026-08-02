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
 * scored the same trace. Identical to {@code ProjectMetricsDAO.TRACE_FILTERED_PREFIX}; the project predicate is
 * injected via {@link #traceFeedbackScoresPrefix(String, String)} so the three workspace query constants stay in sync
 * with the dedup logic.
 */
final class WorkspaceFeedbackScoresQueries {

    private WorkspaceFeedbackScoresQueries() {
    }

    // %s placeholders: project predicate (a workspace-spanning query may pass `project_id IN :project_ids` or omit
    // the project filter entirely, in which case the caller passes an empty string), and the optional name filter
    // (a daily-by-name query passes `AND name = :name`; the summary query passes an empty string). The first four
    // %s slots apply the predicates to each leg of the UNION ALL inside `feedback_scores_deduped`. Predicates are
    // not repeated on `feedback_scores_final`: the deduped rows are already filtered and re-applying on the final
    // aggregation would force a redundant scan.
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
                GROUP BY workspace_id, project_id, entity_id, name
            )
            """;

    /**
     * Returns the {@code feedback_scores_deduped} and {@code feedback_scores_final} CTE preamble for a workspace
     * trace feedback-score query.
     *
     * @param projectPredicate optional {@code project_id IN :project_ids} or {@code project_id = :project_id}
     *                         fragment (must include the leading space if non-empty), or empty string for the
     *                         workspace-wide case
     * @param namePredicate    optional {@code AND name = :name} fragment (must include the leading space if
     *                         non-empty), or empty string when the query aggregates across all score names
     */
    static String traceFeedbackScoresPrefix(String projectPredicate, String namePredicate) {
        return TRACE_FEEDBACK_SCORES_PREFIX_TEMPLATE.formatted(
                projectPredicate, namePredicate,
                projectPredicate, namePredicate);
    }
}
