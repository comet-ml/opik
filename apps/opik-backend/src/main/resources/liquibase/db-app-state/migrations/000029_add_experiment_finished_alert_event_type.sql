--liquibase formatted sql
--changeset BorisTkachenko:000029_add_experiment_finished_alert_event_type
--comment: Add experiment:finished event type to alert_triggers

ALTER TABLE alert_triggers
    MODIFY event_type ENUM(
    'trace:errors',
    'trace:feedback_score',
    'trace_thread:feedback_score',
    'prompt:created',
    'prompt:committed',
    'trace:guardrails_triggered',
    'prompt:deleted',
    'experiment:finished'
    ) NOT NULL;

--rollback empty

