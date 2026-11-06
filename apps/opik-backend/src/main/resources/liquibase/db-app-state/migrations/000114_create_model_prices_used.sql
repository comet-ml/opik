--liquibase formatted sql
--changeset aadereiko:000114_create_model_prices_used
--comment: Create model_prices_used — the models an organization's spans used, per workspace and day (OPIK-9134)

-- One row per organization, day, workspace, model and provider, written at most once a day per combination while
-- spans arrive. Only the last 31 days are kept. The key starts with organization_id and used_on, the read and the
-- clean-up of an organization's days. provider is VARCHAR(150), not 255 like model_prices, so the whole key stays
-- within InnoDB's 3,072-byte limit in utf8mb4.
CREATE TABLE IF NOT EXISTS model_prices_used
(
    organization_id VARCHAR(150) NOT NULL,
    used_on         DATE         NOT NULL,
    workspace_id    VARCHAR(150) NOT NULL,
    workspace_name  VARCHAR(150) NOT NULL DEFAULT '',
    -- Lower case, as the span sent it; '' provider means the span had none.
    model_name      VARCHAR(255) NOT NULL,
    provider        VARCHAR(150) NOT NULL DEFAULT '',
    created_at      TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    CONSTRAINT model_prices_used_pk PRIMARY KEY (organization_id, used_on, workspace_id, model_name, provider)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE IF EXISTS model_prices_used;
