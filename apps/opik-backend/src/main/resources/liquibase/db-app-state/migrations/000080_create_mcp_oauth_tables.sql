--liquibase formatted sql
--changeset avinahradau:000080_create_mcp_oauth_tables
--comment: OAuth 2.1 Authorization Server tables for Opik MCP. Opaque tokens (sha256-hashed), PKCE S256, refresh-token rotation with family revocation. Keyed on user_name/workspace_name to match opik-backend's existing string-identifier convention; no scopes column (tokens grant the user's full workspace role, gated by @RequiredPermissions on each call).

-- Registry of MCP host apps allowed to request tokens. Populated by Dynamic Client Registration
-- (RFC 7591) when remote hosts self-register.
CREATE TABLE mcp_oauth_clients
(
    client_id        VARCHAR(36)  NOT NULL,
    name             VARCHAR(255)  NOT NULL,
    redirect_uris    JSON          NOT NULL,
    logo_uri         VARCHAR(2048) NULL,
    owner_user_name  VARCHAR(255)  NULL,
    created_at       TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (client_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- One-time authorization codes. id is the PK; sha256(code) is enforced unique for lookups.
CREATE TABLE mcp_oauth_codes
(
    id                    CHAR(36)      NOT NULL,
    code_hash             CHAR(64)      NOT NULL,
    client_id             VARCHAR(36)   NOT NULL,
    user_name             VARCHAR(255)  NOT NULL,
    workspace_name        VARCHAR(255)  NOT NULL,
    workspace_id          VARCHAR(255)  NOT NULL,
    code_challenge        VARCHAR(128)  NOT NULL,
    code_challenge_method ENUM('S256')  NOT NULL DEFAULT 'S256',
    redirect_uri          VARCHAR(2048) NOT NULL,
    resource              VARCHAR(2048) NOT NULL,
    created_at            TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at            TIMESTAMP(6)  NOT NULL,
    used_at               TIMESTAMP(6)  NULL,

    PRIMARY KEY (id),
    UNIQUE KEY mcp_oauth_codes_code_hash_uk (code_hash),
    INDEX mcp_oauth_codes_expires_at_idx (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- Opaque access + refresh tokens. id is the PK; sha256(token) is enforced unique for lookups.
-- Raw token returned only once on /oauth/token.
-- family_id groups a refresh-token lineage; gates automatic reuse detection security pattern.
CREATE TABLE mcp_oauth_tokens
(
    id              CHAR(36)               NOT NULL,
    token_hash      CHAR(64)               NOT NULL,
    type            ENUM('access','refresh') NOT NULL,
    client_id       VARCHAR(36)            NOT NULL,
    user_name       VARCHAR(255)           NOT NULL,
    workspace_name  VARCHAR(255)           NOT NULL,
    workspace_id    VARCHAR(255)           NOT NULL,
    resource        VARCHAR(2048)          NOT NULL,
    family_id       CHAR(36)               NOT NULL,
    rotated_from_id CHAR(36)               NULL,
    issued_at       TIMESTAMP(6)           NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at      TIMESTAMP(6)           NOT NULL,
    revoked_at      TIMESTAMP(6)           NULL,
    revoked_reason  VARCHAR(64)            NULL,

    PRIMARY KEY (id),
    UNIQUE KEY mcp_oauth_tokens_token_hash_uk (token_hash),
    INDEX mcp_oauth_tokens_family_idx (family_id, revoked_at),
    INDEX mcp_oauth_tokens_user_workspace_idx (user_name, workspace_name),
    INDEX mcp_oauth_tokens_expires_at_idx (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

--rollback DROP TABLE mcp_oauth_tokens;
--rollback DROP TABLE mcp_oauth_codes;
--rollback DROP TABLE mcp_oauth_clients;
