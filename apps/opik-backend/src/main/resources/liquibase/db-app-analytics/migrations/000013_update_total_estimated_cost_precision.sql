--liquibase formatted sql
--changeset BorisTkachenko:update_total_estimated_cost_precision

ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans
    MODIFY COLUMN total_estimated_cost Decimal128(12);

--rollback ALTER TABLE ${ANALYTICS_DB_DATABASE_NAME}.spans MODIFY COLUMN total_estimated_cost Decimal64(8);
