--liquibase formatted sql
--changeset BorisTkachenko:add_type_optimization_id_to_experiments

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments
    ADD COLUMN IF NOT EXISTS optimization_id String,
    ADD COLUMN IF NOT EXISTS type ENUM('regular' = 0 , 'trial' = 1, 'mini-batch' = 2) DEFAULT 'regular';

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments DROP COLUMN IF EXISTS optimization_id, DROP COLUMN IF EXISTS type;
