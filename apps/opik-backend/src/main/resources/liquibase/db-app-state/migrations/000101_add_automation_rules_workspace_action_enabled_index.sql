--liquibase formatted sql
--changeset aliaksandrk:000101_add_automation_rules_workspace_action_enabled_index
--comment: Index automation_rules by (workspace_id, action, enabled) so the queue-routing guard stops scanning a workspace's every rule (OPIK-6303)

-- The guard asks whether a workspace has any enabled router of a given scope, and runs on every batch
-- feedback-score event. automation_rules_idx starts (workspace_id, project_id), and project_id is NULL for
-- every rule written since the junction table arrived, so the only usable prefix was workspace_id: a
-- workspace with 20k evaluators cost 20k index entries plus a primary-key probe per entry, measured at
-- 46.8ms to answer "no". Narrowing by action first turns that into 500 entries and 1.1ms, read entirely
-- from the index.
--
-- id is deliberately not listed: InnoDB appends the primary key to every secondary index, so the lookup
-- is covering without it.
CREATE INDEX automation_rules_workspace_action_enabled_idx
    ON automation_rules(workspace_id, action, enabled);

--rollback DROP INDEX automation_rules_workspace_action_enabled_idx ON automation_rules;
