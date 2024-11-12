--liquibase formatted sql
--changeset thiagohora:change_decimal_precision

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans
    MODIFY COLUMN usage Map(String, Nullable(Int32));

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans MODIFY COLUMN usage Map(String, Int32);
