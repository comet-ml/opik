--liquibase formatted sql
--changeset aliaksandrk:000120_add_annotation_queue_item_history_table
--comment: Create annotation_queue_item_history — append-only record of every item ever added to a queue, so automation never adds the same one twice (OPIK-6303)

-- Rows accumulate and outlive the item's membership: removeItems issues a DELETE against
-- annotation_queue_items, so that table only knows what is in a queue right now. Without this history a
-- hand-removed item would match the automation conditions again and be re-added on the next run.
-- Written for manual adds too — a trace a human queued must not later be re-added by the sweep.
--
-- Deliberately a pure set; existence is the only question it answers. Provenance stays on
-- annotation_queue_items.source/created_by, which is accurate for as long as anyone can still ask.
--
-- The considered alternative was a soft-delete flag on annotation_queue_items, which needs no new table
-- but adds a predicate to every existing join on it (ThreadDAO, TraceDAO, the item-count CTE), where one
-- omission silently resurrects removed items into a reviewer's queue. Revisit if this table becomes a
-- maintenance burden.
CREATE TABLE IF NOT EXISTS ${ANALYTICS_DB_DATABASE_NAME}.annotation_queue_item_history ON CLUSTER '{cluster}'
(
    workspace_id String,
    project_id   FixedString(36),
    queue_id     FixedString(36),
    item_id      FixedString(36),
    added_at     DateTime64(9, 'UTC') DEFAULT now64(9)
)
ENGINE = ReplicatedReplacingMergeTree('/clickhouse/tables/{shard}/${ANALYTICS_DB_DATABASE_NAME}/annotation_queue_item_history', '{replica}', added_at)
ORDER BY (workspace_id, project_id, queue_id, item_id)
SETTINGS index_granularity = 8192;

--rollback DROP TABLE IF EXISTS ${ANALYTICS_DB_DATABASE_NAME}.annotation_queue_item_history ON CLUSTER '{cluster}';
