--liquibase formatted sql
--changeset BorisTkachenko:000026_change_webhooks_secret_token_as_nullable

ALTER TABLE webhooks
    MODIFY COLUMN secret_token VARCHAR(250) NULL;

--rollback ALTER TABLE webhooks MODIFY COLUMN secret_token VARCHAR(250) NOT NULL;
