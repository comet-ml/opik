--liquibase formatted sql
--changeset danield:000084_normalize_dataset_item_id_to_stable_in_experiment_item_aggregates
--comment: OPIK-6177 — backfill experiment_item_aggregates.dataset_item_id to be the stable dataset_item_id (vs per-version row_id) for legacy rows written before the OPIK-4518 BE cutover. Lets the compare query reference eia.dataset_item_id directly without a per-query lookup_div LEFT JOIN, restoring skip-index pushdown.

-- experiment_item_aggregates is a ReplicatedReplacingMergeTree ordered by
-- (workspace_id, experiment_id, id) with last_updated_at as the version column.
-- Re-inserting a row with the same PK and a fresher last_updated_at supersedes
-- the old row on next merge (FINAL reads see the new version immediately).
--
-- WHERE filter: only rewrite rows where the lookup actually changes the value.
-- For modern writes (post-OPIK-4518), eia.dataset_item_id IS already the stable id
-- and either: (a) matches a v1 DIV row whose dataset_item_id == its own id (no-op),
-- or (b) doesn't match any DIV row (no-op via fallback). For legacy writes,
-- eia.dataset_item_id is a per-version DIV row id, so lookup_div.id matches a row
-- whose dataset_item_id IS the stable id and differs from eia.dataset_item_id —
-- those are the rows we want to rewrite.
INSERT INTO ${ANALYTICS_DB_DATABASE_NAME}.experiment_item_aggregates
(
    workspace_id,
    id,
    project_id,
    experiment_id,
    dataset_item_id,
    trace_id,
    input,
    output,
    input_slim,
    output_slim,
    duration,
    total_estimated_cost,
    usage,
    feedback_scores,
    feedback_scores_array,
    visibility_mode,
    created_at,
    last_updated_at,
    created_by,
    last_updated_by,
    metadata,
    comments_array_agg,
    execution_policy,
    assertions_array
)
SELECT
    eia.workspace_id,
    eia.id,
    eia.project_id,
    eia.experiment_id,
    lookup_div.dataset_item_id          AS dataset_item_id, -- the stable id
    eia.trace_id,
    eia.input,
    eia.output,
    eia.input_slim,
    eia.output_slim,
    eia.duration,
    eia.total_estimated_cost,
    eia.usage,
    eia.feedback_scores,
    eia.feedback_scores_array,
    eia.visibility_mode,
    eia.created_at,
    now64(9)                            AS last_updated_at, -- supersedes the legacy row in ReplacingMergeTree
    eia.created_by,
    eia.last_updated_by,
    eia.metadata,
    eia.comments_array_agg,
    eia.execution_policy,
    eia.assertions_array
FROM ${ANALYTICS_DB_DATABASE_NAME}.experiment_item_aggregates AS eia FINAL
INNER JOIN ${ANALYTICS_DB_DATABASE_NAME}.dataset_item_versions AS lookup_div FINAL
    ON lookup_div.workspace_id = eia.workspace_id
    AND lookup_div.id = eia.dataset_item_id
WHERE notEmpty(lookup_div.dataset_item_id)
  AND lookup_div.dataset_item_id != eia.dataset_item_id;

--rollback -- empty
--rollback -- Note: rewriting eia.dataset_item_id back to the per-version row_id requires
--rollback -- knowing the original value, which was overwritten by this migration. Manual
--rollback -- recovery would require restoring from backup.
