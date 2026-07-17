--liquibase formatted sql
--changeset alexkuzmik:000090_add_filter_guardrail_type_config_type
--comment: Add filter:guardrail_type config type to alert_trigger_configs

ALTER TABLE alert_trigger_configs
    MODIFY config_type ENUM(
    'scope:project',
    'threshold:feedback_score',
    'threshold:cost',
    'threshold:latency',
    'threshold:errors',
    'filter:guardrail_type'
    ) NOT NULL;

--rollback empty
