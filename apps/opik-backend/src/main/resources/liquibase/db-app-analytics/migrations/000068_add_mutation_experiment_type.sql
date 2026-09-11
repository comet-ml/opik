--liquibase formatted sql
--changeset itamarg:000068_add_mutation_experiment_type
--comment: Add 'mutation' value to experiment type enum in experiments and experiment_aggregates tables

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    MODIFY COLUMN IF EXISTS type Enum8('regular' = 0, 'trial' = 1, 'mini-batch' = 2, 'mutation' = 3) DEFAULT 'regular';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}'
    MODIFY COLUMN IF EXISTS type Enum8('regular' = 0, 'trial' = 1, 'mini-batch' = 2, 'mutation' = 3) DEFAULT 'regular';

--rollback -- Forward-only migration: ClickHouse enum values cannot be safely removed once rows reference them.
--rollback -- Recovery: restore from backup, or run a cleanup migration to convert/delete rows with 'mutation' type before reverting schema.
