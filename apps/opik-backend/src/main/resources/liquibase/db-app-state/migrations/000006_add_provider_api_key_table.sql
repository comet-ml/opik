--liquibase formatted sql
--changeset BorisTkachenko:000006_add_llm_provider_api_key_table

CREATE TABLE IF NOT EXISTS llm_provider_api_key (
    id CHAR(36) NOT NULL,
    provider VARCHAR(250) NOT NULL,
    workspace_id VARCHAR(150) NOT NULL,
    api_key VARCHAR(250) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    last_updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by VARCHAR(100) NOT NULL DEFAULT 'admin',
    CONSTRAINT `llm_provider_api_key_pk` PRIMARY KEY (id),
    CONSTRAINT `llm_provider_api_key_workspace_id_provider` UNIQUE (workspace_id, provider)
    );

--rollback DROP TABLE IF EXISTS llm_provider_api_key;
