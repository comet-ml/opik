--liquibase formatted sql
--changeset itamarg:000063_add_mutation_experiment_type
--comment: Add 'mutation' value to experiment type enum in experiments and experiment_aggregates tables

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments ON CLUSTER '{cluster}'
    MODIFY COLUMN IF EXISTS type Enum8('regular' = 0, 'trial' = 1, 'mini-batch' = 2, 'mutation' = 3) DEFAULT 'regular';

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiment_aggregates ON CLUSTER '{cluster}'
    MODIFY COLUMN IF EXISTS type Enum8('regular' = 0, 'trial' = 1, 'mini-batch' = 2, 'mutation' = 3) DEFAULT 'regular';
