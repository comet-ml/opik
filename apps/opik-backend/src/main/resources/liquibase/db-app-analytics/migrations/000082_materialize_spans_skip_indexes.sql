--liquibase formatted sql
--changeset thiagohora:000082_materialize_spans_skip_indexes
--comment: Materialize skip indexes on spans (~19.5TB/1.42B rows). Apply after 000081 mutations complete: SELECT * FROM system.mutations WHERE is_done = 0 AND table = 'spans'.

-- Materialize index from 000075 (idx_spans_source was added without materialization)
ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}'
    MATERIALIZE INDEX idx_spans_source;

--rollback empty
