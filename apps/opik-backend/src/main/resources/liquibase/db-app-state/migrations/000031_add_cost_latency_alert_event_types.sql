--liquibase formatted sql
--changeset BorisTkachenko:000031_add_cost_latency_alert_event_types
--comment: Add trace:cost and trace:latency event types to alert_triggers

ALTER TABLE alert_triggers
    MODIFY event_type ENUM(
    'trace:errors',
    'trace:feedback_score',
    'trace_thread:feedback_score',
    'prompt:created',
    'prompt:committed',
    'trace:guardrails_triggered',
    'prompt:deleted',
    'experiment:finished',
    'trace:cost',
    'trace:latency'
    ) NOT NULL;

--rollback empty


