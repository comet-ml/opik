--liquibase formatted sql
--changeset alexkuzmik:000091_add_filter_guardrail_type_config_type
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

-- Forward-only (matches 000033/000035): reverting is unsafe once any alert_trigger_configs row
-- uses 'filter:guardrail_type', since MODIFY-ing the enum back would truncate those values. Recovery
-- means restoring the previous enum only after deleting/migrating such rows.
--rollback empty

