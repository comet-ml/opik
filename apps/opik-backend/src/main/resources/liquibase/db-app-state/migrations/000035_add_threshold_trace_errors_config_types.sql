--liquibase formatted sql
--changeset BorisTkachenko:000035_add_threshold_trace_errors_config_types
--comment: Add threshold:errors config type to alert_trigger_configs

ALTER TABLE alert_trigger_configs
    MODIFY config_type ENUM(
    'scope:project',
    'threshold:feedback_score',
    'threshold:cost',
    'threshold:latency',
    'threshold:errors'
    ) NOT NULL;

--rollback empty


