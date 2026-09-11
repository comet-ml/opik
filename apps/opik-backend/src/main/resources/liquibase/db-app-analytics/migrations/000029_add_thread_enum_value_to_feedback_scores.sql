--liquibase formatted sql
--changeset thiagohora:000029_add_thread_enum_value_to_feedback_scores

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores ON CLUSTER '{cluster}'
    MODIFY COLUMN entity_type ENUM('unknown' = 0 , 'span' = 1, 'trace' = 2, 'thread' = 3);

--rollback empty
