--liquibase formatted sql
--changeset thiaghora:000024_create_alerts_webhooks_tables

-- 1. Webhooks table 
CREATE TABLE webhooks (
  id                   CHAR(36)      PRIMARY KEY,
  name                 VARCHAR(255)  NOT NULL,
  url                  TEXT          NOT NULL, -- Webhook URLs with reasonable length limit
  secret_token         TEXT          NOT NULL,
  headers              JSON          NULL, -- Map<String,String>
  created_by           VARCHAR(255)  NOT NULL,
  last_updated_by      VARCHAR(255)  NOT NULL,
  created_at           TIMESTAMP(6)  NOT NULL DEFAULT NOW(6),
  last_updated_at      TIMESTAMP(6)  NOT NULL DEFAULT NOW(6),
  workspace_id         VARCHAR(150)  NOT NULL,
  UNIQUE KEY uk_webhooks_workspace_id (workspace_id, id)
);

-- 2. Alerts table
CREATE TABLE alerts (
  id                   CHAR(36)      PRIMARY KEY,
  name                 VARCHAR(255)  NOT NULL,
  enabled              BOOLEAN       NOT NULL DEFAULT TRUE,
  workspace_id         VARCHAR(150)  NOT NULL,
  created_by           VARCHAR(255)  NOT NULL,
  last_updated_by      VARCHAR(255)  NOT NULL,
  webhook_id           CHAR(36)      NOT NULL,
  created_at           TIMESTAMP(6)  NOT NULL DEFAULT NOW(6),
  last_updated_at      TIMESTAMP(6)  NOT NULL DEFAULT NOW(6),
  UNIQUE KEY uk_alerts_workspace_id (workspace_id, id),
  CONSTRAINT fk_alerts_webhook FOREIGN KEY (webhook_id) REFERENCES webhooks(id)
);

-- 3. Alert Trigger Configurations table
CREATE TABLE alert_triggers (
  id                   CHAR(36)      PRIMARY KEY,
  alert_id             CHAR(36)      NOT NULL,
  event_type           VARCHAR(100)  NOT NULL,
  created_by           VARCHAR(255)  NOT NULL,
  last_updated_by      VARCHAR(255)  NOT NULL,
  created_at           TIMESTAMP(6)  NOT NULL DEFAULT NOW(6),
  CONSTRAINT fk_alert_triggers_alert FOREIGN KEY (alert_id) REFERENCES alerts(id)
);

-- 4. Alert Trigger Configurations table
CREATE TABLE alert_trigger_configs (
  id                   CHAR(36)      PRIMARY KEY,
  alert_trigger_id     CHAR(36)      NOT NULL,
  config_type          VARCHAR(100)  NOT NULL,
  config_value         JSON          NOT NULL,
  created_by           VARCHAR(255)  NOT NULL,
  last_updated_by      VARCHAR(255)  NOT NULL,
  created_at           TIMESTAMP(6)  NOT NULL DEFAULT NOW(6),
  last_updated_at      TIMESTAMP(6)  NOT NULL DEFAULT NOW(6),
  CONSTRAINT fk_alert_trigger_configs_alert_trigger FOREIGN KEY (alert_trigger_id) REFERENCES alert_triggers(id),
  UNIQUE KEY uk_alert_trigger_configs_alert_trigger_id (alert_trigger_id, config_type)
);

--rollback DROP TABLE alert_triggers;
--rollback DROP TABLE alerts;
--rollback DROP TABLE webhooks; 
--rollback DROP TABLE alert_trigger_configs;
