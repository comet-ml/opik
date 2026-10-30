--liquibase formatted sql
--changeset yaroslavb:000113_create_topic_workspaces
--comment: The topics opt-in: workspaces whose labeled traces get topic vectors and topics while topics.enabled is on (OPIK-8981)

-- Topics need the ai-labels trace_summary, so a workspace runs topics only when it is enabled here and in
-- ai_label_workspaces. Onboarding is a row change, not a deploy. A disabled workspace keeps its row and its data; the
-- next tick and the next night drop it. Read in full every tick, so it needs no index beyond the key.
CREATE TABLE topic_workspaces
(
    workspace_id VARCHAR(150) NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    added_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    added_by     VARCHAR(100) NOT NULL,

    PRIMARY KEY (workspace_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS topic_workspaces;
