--liquibase formatted sql
--changeset DanielAugusto:000009_extend_feedback_source_type

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores
    MODIFY COLUMN `source` Enum8('sdk', 'ui', 'online_scoring');

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores MODIFY COLUMN `source` Enum8('sdk', 'ui');
