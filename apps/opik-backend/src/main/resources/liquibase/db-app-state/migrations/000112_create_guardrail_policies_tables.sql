--liquibase formatted sql
--changeset aliaksandrk:000112_create_guardrail_policies_tables
--comment: Create guardrail_policies and guardrail_policy_rules — named, workspace-scoped guardrail policies and their per-type rules (OPIK-8803)

-- A policy is what the SDK names on a check and what the UI edits. Its rules are rows in the child table
-- below rather than a JSON column, so each rule can be read on its own and a new rule type needs no
-- reshaping of stored data. Only what the user set is stored: thresholds, question wording and the model
-- are backend configuration versioned with the code, and every result row in ClickHouse snapshots its
-- rule's config at check time, so policies need no versioning.
CREATE TABLE IF NOT EXISTS guardrail_policies
(
    id              CHAR(36)                     NOT NULL,
    workspace_id    VARCHAR(150)                 NOT NULL,
    name            VARCHAR(255)                 NOT NULL,
    description     VARCHAR(1000)                NULL,
    -- ON_REQUEST applies when the SDK names the policy; GLOBAL applies to every check in the workspace. Values are
    -- stored exactly as the API spells them, like dataset_export_jobs.status, so no mapping sits between the
    -- column and the Java enum. A new scope is a MODIFY COLUMN migration, forward-only like every ENUM widening
    -- in this changelog (000019, 000063).
    execution_scope ENUM('ON_REQUEST', 'GLOBAL') NOT NULL,
    created_at      TIMESTAMP(6)                 NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by      VARCHAR(100)                 NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6)                 NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100)                 NOT NULL DEFAULT 'admin',

    CONSTRAINT guardrail_policies_pk PRIMARY KEY (id),
    -- The SDK addresses a policy by name, so the name is the stable identifier and unique per workspace.
    -- The schema collation (utf8mb4_unicode_ci) makes both the constraint and the retrieve-by-name lookup
    -- case-insensitive. This key also serves the `name IN (...)` half of the check path.
    CONSTRAINT guardrail_policies_workspace_name_uk UNIQUE (workspace_id, name),
    -- The default list order, `WHERE workspace_id = ? ORDER BY id DESC`, which the unique key above
    -- (ordered by name) cannot serve.
    INDEX guardrail_policies_workspace_id_idx (workspace_id, id),
    -- The check path runs on every SDK validate call and loads the named policies plus every GLOBAL policy
    -- of the workspace. The GLOBAL half has no name to look up, so without this index it reads the
    -- workspace's every policy; with it, only the global ones. It is used when that half is its own lookup
    -- (a UNION with the names lookup, or a second statement): MySQL 8.4 answers a single `name IN (...) OR
    -- execution_scope = 'GLOBAL'` by scanning the workspace prefix of the unique key instead. id is not
    -- listed: InnoDB appends the primary key to every secondary index.
    INDEX guardrail_policies_workspace_execution_scope_idx (workspace_id, execution_scope)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

-- One row per check type of a policy, holding only what the user set.
--
-- No foreign key to guardrail_policies, like every other state table since 000042: the service writes and
-- deletes a policy's rules with the policy in one transaction. workspace_id is deliberately absent: a rule
-- is only ever reached through its policy, which carries the workspace.
CREATE TABLE IF NOT EXISTS guardrail_policy_rules
(
    id              CHAR(36)     NOT NULL,
    policy_id       CHAR(36)     NOT NULL,
    -- The GuardrailType values in the Java enum's order, spelled as the API and the SDKs send them and as the
    -- ClickHouse guardrails results table stores them in `name`, so a result is readable against its rule and
    -- no mapping sits between the column and the Java enum. Which of them a policy may hold is the service's
    -- call (milestone 1: TOPIC, PII, PROMPT_INJECTION); a new Java value gets a MODIFY COLUMN migration, as in
    -- 000019 and 000063.
    type            ENUM('TOPIC', 'PII', 'LLM_JUDGE', 'PROMPT_INJECTION', 'CUSTOM_CLASSIFIER') NOT NULL,
    -- Exactly what the user set: PII classes, allowed and restricted topics. An empty object for a type with
    -- no settings (prompt injection). Never thresholds, model names or question templates.
    config          JSON         NOT NULL,
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by      VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',

    CONSTRAINT guardrail_policy_rules_pk PRIMARY KEY (id),
    -- A policy has at most one rule per check type. policy_id leads, so this key also serves every read and
    -- delete of a policy's rules (`WHERE policy_id IN (...)`), the only way rules are reached.
    CONSTRAINT guardrail_policy_rules_policy_id_type_uk UNIQUE (policy_id, type)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS guardrail_policy_rules;
--rollback DROP TABLE IF EXISTS guardrail_policies;
