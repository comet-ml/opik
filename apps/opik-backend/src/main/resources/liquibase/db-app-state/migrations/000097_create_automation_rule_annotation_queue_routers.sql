--liquibase formatted sql
--changeset aliaksandrk:000097_add_annotation_queue_router_action
--comment: Allow automation_rules to describe a queue-population rule (OPIK-6303)

-- Queue automation is an automation rule, so it is a row in automation_rules with its own action rather
-- than a parallel table. The parent supplies what is true of every rule — workspace, project, enabled,
-- name, sampling rate — and the subtype below supplies only what is specific to populating a queue.
ALTER TABLE automation_rules MODIFY COLUMN `action` ENUM('evaluator', 'annotation_queue_router') NOT NULL;

--rollback ALTER TABLE automation_rules MODIFY COLUMN `action` ENUM('evaluator') NOT NULL;

--changeset aliaksandrk:000097_create_automation_rule_annotation_queue_routers
--comment: Create automation_rule_annotation_queue_routers — the queue-population subtype of automation_rules (OPIK-6303)

-- Mirrors automation_rule_evaluators: the primary key is the parent rule's id, and workspace and project
-- are deliberately absent because they are the parent's and reached by joining it.
--
-- scope lives here rather than on the parent: it says whether this rule matches traces or threads, which
-- only means something for a rule that populates a queue. The parent has no such column today.
CREATE TABLE IF NOT EXISTS automation_rule_annotation_queue_routers (
    id CHAR(36),

    queue_id CHAR(36) NOT NULL,
    scope ENUM('trace', 'thread') NOT NULL,
    conditions JSON NOT NULL,
    -- Ceiling on how many items automation may leave in the queue. NULL means no ceiling, which is why
    -- this is nullable rather than a sentinel like 0 — "unbounded" is the default state, not a magic value.
    max_items_in_queue INT UNSIGNED NULL,

    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',

    CONSTRAINT automation_rule_annotation_queue_routers_pk PRIMARY KEY (id),
    -- A queue has at most one automation, and the queue is how the API addresses it: every read starts
    -- from a queue id, not a rule id.
    CONSTRAINT automation_rule_annotation_queue_routers_queue_uk UNIQUE (queue_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS automation_rule_annotation_queue_routers;
