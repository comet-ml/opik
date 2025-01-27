--liquibase formatted sql
--changeset BorisTkachenko:000012_add_comments_entity_type_span

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.comments
    MODIFY COLUMN `entity_type` ENUM('trace', 'span');

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.comments MODIFY COLUMN `source` ENUM('trace');
