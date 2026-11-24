--liquibase formatted sql
--changeset yaroslavb:000121_add_long_window_read_at_to_topic_vector_watermarks
--comment: topic_vector_watermarks.long_window_read_at: the daily long-window pass's progress per workspace (OPIK-8981)

-- long_window_read_at: the start of the tick whose long-window pass last read the workspace completely, so a pass that
-- takes several ticks reads each workspace once. Null until a pass does.
ALTER TABLE topic_vector_watermarks
    ADD COLUMN long_window_read_at TIMESTAMP(6) NULL;

--rollback ALTER TABLE topic_vector_watermarks DROP COLUMN long_window_read_at;
