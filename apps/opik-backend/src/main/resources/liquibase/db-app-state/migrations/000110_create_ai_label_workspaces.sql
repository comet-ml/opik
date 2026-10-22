--liquibase formatted sql
--changeset collinc:000110_create_ai_label_workspaces
--comment: The ai-labels opt-in: workspaces whose ended traces are labeled while aiLabels.enabled is on

-- Onboarding is a row change, not a deploy. A disabled workspace keeps its row (and its labels); the next sweep drops
-- it. Read in full every sweep, so it needs no index beyond the key.
CREATE TABLE ai_label_workspaces
(
    workspace_id VARCHAR(150) NOT NULL,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    added_at     TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    added_by     VARCHAR(100) NOT NULL,

    PRIMARY KEY (workspace_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS ai_label_workspaces;

