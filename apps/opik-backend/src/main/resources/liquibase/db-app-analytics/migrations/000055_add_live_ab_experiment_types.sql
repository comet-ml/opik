--liquibase formatted sql
--changeset BotCopilot:000055_add_live_ab_experiment_types

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.experiments MODIFY COLUMN type Enum8('regular' = 0, 'trial' = 1, 'mini-batch' = 2, 'live' = 3, 'ab' = 4) DEFAULT 'regular';
