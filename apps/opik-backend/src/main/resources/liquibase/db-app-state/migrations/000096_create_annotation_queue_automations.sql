--liquibase formatted sql
--changeset aliaksandrk:000096_create_annotation_queue_automations
--comment: Create annotation_queue_automations — per-queue configuration for automatic queue population (OPIK-6303)

-- The queue itself lives in ClickHouse (annotation_queues, a ReplacingMergeTree where an update means
-- inserting a whole new row, bumping last_updated_at — which the UI shows as "Last updated"). Automation
-- config lives here instead so editing it is a normal mutable update rather than a new queue version.
--
-- project_id and scope are denormalised from the queue row. Safe because AnnotationQueueUpdate exposes
-- neither, so both are immutable for a queue's lifetime and the copy cannot drift. It saves the event
-- listener a ClickHouse round trip per automation just to learn which entity type it evaluates.
--
-- No surrogate id: the row is 1:1 with a queue, so the natural key is the primary key, which enforces
-- the 1:1 in the schema rather than via a separate unique constraint.
--
-- Pure configuration, no run state: routing is driven by event subscriptions, so there is no scan whose
-- progress needs recording. Backfill of pre-existing items is out of scope, which is why there is no
-- window floor either — an automation only ever sees events that arrive after it is enabled.
CREATE TABLE IF NOT EXISTS annotation_queue_automations (
    workspace_id VARCHAR(150) NOT NULL,
    queue_id CHAR(36) NOT NULL,
    project_id CHAR(36) NOT NULL,
    scope ENUM('trace', 'thread') NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    conditions JSON NOT NULL,
    -- Ceiling on how many items automation may leave in the queue. NULL means no ceiling, which is why
    -- this is nullable rather than a sentinel like 0 — "unbounded" is the default state, not a magic value.
    max_items_in_queue INT UNSIGNED NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(255) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(255) NOT NULL DEFAULT 'admin',
    CONSTRAINT annotation_queue_automations_pk PRIMARY KEY (workspace_id, queue_id),
    -- Routing looks automations up by the projects an event touches, which the primary key cannot serve
    -- because it leads with queue_id after workspace_id. enabled is included so the common
    -- "enabled automations for these projects" lookup is answered from the index alone.
    INDEX annotation_queue_automations_project_idx (workspace_id, project_id, enabled)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS annotation_queue_automations;
