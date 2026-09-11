--liquibase formatted sql
--changeset BorisTkachenko:000031_extend_comments_entity_type_thread

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.comments ON CLUSTER '{cluster}'
    MODIFY COLUMN `entity_type` ENUM('trace', 'span', 'thread');

-- rollback empty
