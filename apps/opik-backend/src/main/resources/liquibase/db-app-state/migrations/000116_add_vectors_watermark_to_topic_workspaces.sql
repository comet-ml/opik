--liquibase formatted sql
--changeset yaroslavb:000116_add_vectors_watermark_to_topic_workspaces
--comment: Add the vectors tick's per-workspace watermark to topic_workspaces (OPIK-8981)

-- vectors_watermark: where the vectors tick's next window read of the workspace starts, less a small overlap. The tick
-- moves it past the done statuses it gave a vector, and never past one it found and left; null until the tick first
-- reads the workspace. The tick now writes a workspace's row before the backfill discovers its projects, so
-- vectors_checked_at is null until the discovery does.
ALTER TABLE topic_workspaces
    ADD COLUMN vectors_watermark TIMESTAMP(6) NULL DEFAULT NULL AFTER vectors_checked_at,
    MODIFY COLUMN vectors_checked_at TIMESTAMP(6) NULL DEFAULT NULL;

-- Making vectors_checked_at nullable is an in-place change, so the rollback only drops the added column.
--rollback ALTER TABLE topic_workspaces DROP COLUMN vectors_watermark;
