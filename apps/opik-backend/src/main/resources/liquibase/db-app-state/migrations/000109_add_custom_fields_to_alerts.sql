--liquibase formatted sql
--changeset danield:000109_add_custom_fields_to_alerts
--comment: Add user-defined custom fields to alerts, published as top-level keys of the alert's EventBridge event detail

ALTER TABLE alerts
    ADD COLUMN custom_fields JSON NULL;

--rollback ALTER TABLE alerts DROP COLUMN custom_fields;
