--liquibase formatted sql
--changeset miguelg:000094_add_failure_reason_to_ollie_reports
--comment: Add failure_reason to ollie_reports so a failed daily report can say why (OPIK-7692)

ALTER TABLE ollie_reports ADD COLUMN failure_reason VARCHAR(64) NULL;

--rollback ALTER TABLE ollie_reports DROP COLUMN failure_reason;
