--liquibase formatted sql
--changeset thiagohora:change_decimal_precision

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores
    MODIFY COLUMN value Decimal64(9);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.feedback_scores MODIFY COLUMN value Decimal32(4);
