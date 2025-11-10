--liquibase formatted sql
--changeset BorisTkachenko:000027_change_alert_trigger_type

ALTER TABLE alert_triggers
    MODIFY event_type ENUM(
    'trace:errors',
    'trace:feedback_score',
    'trace_thread:feedback_score',
    'prompt:created',
    'prompt:committed',
    'trace:guardrails_triggered',  -- updated here to trace from span
    'prompt:deleted'
    ) NOT NULL;

--rollback empty
