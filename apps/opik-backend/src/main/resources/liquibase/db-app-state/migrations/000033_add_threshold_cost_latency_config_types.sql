--liquibase formatted sql
--changeset BorisTkachenko:000033_add_threshold_cost_latency_config_types
--comment: Add threshold:cost and threshold:latency config types to alert_trigger_configs

ALTER TABLE alert_trigger_configs
    MODIFY config_type ENUM(
    'scope:project',
    'threshold:feedback_score',
    'threshold:cost',
    'threshold:latency'
    ) NOT NULL;

--rollback empty


