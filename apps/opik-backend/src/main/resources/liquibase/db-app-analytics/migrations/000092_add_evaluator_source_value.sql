--liquibase formatted sql
--changeset aliaksandrk:000092_add_evaluator_source_value

--comment: Add 'evaluator' to the source Enum8 on traces and spans so online-evaluation monitoring
--comment: traces/spans (the persisted LLM-as-judge loop) carry their origin and are hidden/isolated
--comment: from default views the same way other non-SDK sources are (OPIK-6994).

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}'
    MODIFY COLUMN IF EXISTS source Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4, 'evaluator' = 5) DEFAULT 'unknown';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}'
    MODIFY COLUMN IF EXISTS source Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4, 'evaluator' = 5) DEFAULT 'unknown';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.traces ON CLUSTER '{cluster}' MODIFY COLUMN IF EXISTS source Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4) DEFAULT 'unknown';
--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans ON CLUSTER '{cluster}' MODIFY COLUMN IF EXISTS source Enum8('unknown' = 0, 'sdk' = 1, 'experiment' = 2, 'playground' = 3, 'optimization' = 4) DEFAULT 'unknown';
