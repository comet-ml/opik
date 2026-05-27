--liquibase formatted sql
--changeset avinahradau:000077_create_mcp_oauth_tables
--comment: OAuth 2.1 Authorization Server tables for Opik MCP. Opaque tokens (sha256-hashed), PKCE S256, refresh-token rotation with family revocation. Keyed on user_name/workspace_name to match opik-backend's existing string-identifier convention; no scopes column (tokens grant the user's full workspace role, gated by @RequiredPermissions on each call).

-- Registry of MCP host apps allowed to request tokens. Populated by the opik-mcp-local seed below,
-- by Dynamic Client Registration (RFC 7591) when remote hosts self-register, and admin endpoint.
CREATE TABLE mcp_oauth_clients
(
    client_id        VARCHAR(150)  NOT NULL,
    name             VARCHAR(255)  NOT NULL,
    redirect_uris    JSON          NOT NULL,
    logo_uri         VARCHAR(2048) NULL,
    owner_user_name  VARCHAR(100)  NULL,
    created_at       TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    created_by       VARCHAR(100)  NOT NULL DEFAULT 'admin',
    last_updated_at  TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    last_updated_by  VARCHAR(100)  NOT NULL DEFAULT 'admin',
    revoked_at       TIMESTAMP(6)  NULL,

    PRIMARY KEY (client_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- One-time authorization codes. sha256(code) is the PK; raw code returned only on the 302 redirect.
CREATE TABLE mcp_oauth_codes
(
    code_hash             CHAR(64)      NOT NULL,
    client_id             VARCHAR(150)  NOT NULL,
    user_name             VARCHAR(100)  NOT NULL,
    workspace_name        VARCHAR(255)  NOT NULL,
    workspace_id          VARCHAR(150)  NOT NULL,
    code_challenge        VARCHAR(128)  NOT NULL,
    code_challenge_method ENUM('S256')  NOT NULL DEFAULT 'S256',
    redirect_uri          VARCHAR(2048) NOT NULL,
    resource              VARCHAR(2048) NOT NULL,
    created_at            TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at            TIMESTAMP(6)  NOT NULL,
    used_at               TIMESTAMP(6)  NULL,

    PRIMARY KEY (code_hash),
    INDEX mcp_oauth_codes_expires_at_idx (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- Opaque access + refresh tokens. sha256(token) is the PK; raw token returned only once on /oauth/token.
-- family_id groups a refresh-token lineage; gates automatic reuse detection security pattern.
CREATE TABLE mcp_oauth_tokens
(
    token_hash      CHAR(64)               NOT NULL,
    type            ENUM('access','refresh') NOT NULL,
    client_id       VARCHAR(150)           NOT NULL,
    user_name       VARCHAR(100)           NOT NULL,
    workspace_name  VARCHAR(255)           NOT NULL,
    workspace_id    VARCHAR(150)           NOT NULL,
    resource        VARCHAR(2048)          NOT NULL,
    family_id       CHAR(36)               NOT NULL,
    rotated_from    CHAR(64)               NULL,
    issued_at       TIMESTAMP(6)           NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at      TIMESTAMP(6)           NOT NULL,
    revoked_at      TIMESTAMP(6)           NULL,
    revoked_reason  VARCHAR(64)            NULL,

    PRIMARY KEY (token_hash),
    INDEX mcp_oauth_tokens_family_idx (family_id, revoked_at),
    INDEX mcp_oauth_tokens_user_workspace_idx (user_name, workspace_name),
    INDEX mcp_oauth_tokens_expires_at_idx (expires_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4;

-- Seeds only opik-mcp-local: the one client whose configuration we control (the local install bakes in
-- this client_id). Remote AI hosts (Claude Code, Cursor, claude.ai, VS Code Copilot) obtain a client_id
-- through Dynamic Client Registration at connect time and declare their own redirect_uris then.
INSERT INTO mcp_oauth_clients (client_id, name, redirect_uris, logo_uri) VALUES
    ('opik-mcp-local', 'Opik MCP (local install)', JSON_ARRAY('http://127.0.0.1/callback', 'http://localhost/callback'), NULL);

--rollback DROP TABLE mcp_oauth_tokens;
--rollback DROP TABLE mcp_oauth_codes;
--rollback DROP TABLE mcp_oauth_clients;
