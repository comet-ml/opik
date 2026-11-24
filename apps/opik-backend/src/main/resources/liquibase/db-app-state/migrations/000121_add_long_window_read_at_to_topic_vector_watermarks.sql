--liquibase formatted sql
--changeset yaroslavb:000121_add_long_window_read_at_to_topic_vector_watermarks
--comment: topic_vector_watermarks.long_window_read_at: the daily long-window pass's progress per workspace (OPIK-8981)

-- long_window_read_at: the start of the tick whose long-window pass last read the workspace completely, so a pass that
-- takes several ticks reads each workspace once. A pass can read a workspace before its first fresh read, so the
-- watermark may be absent.
ALTER TABLE topic_vector_watermarks
    MODIFY watermark TIMESTAMP(6) NULL,
    ADD COLUMN long_window_read_at TIMESTAMP(6) NULL;

--rollback DELETE FROM topic_vector_watermarks WHERE watermark IS NULL;
--rollback ALTER TABLE topic_vector_watermarks DROP COLUMN long_window_read_at, MODIFY watermark TIMESTAMP(6) NOT NULL;
