--liquibase formatted sql
--changeset yaroslavb:000097_create_mcp_client_connections
--comment: Durable record of which MCP clients a user has connected. Drives the opik_mcp_connected BI event (fired once per client, not once per grant) and backs a future "connected MCP clients" UI. Deliberately NOT scrubbed: mcp_oauth_tokens rows are deleted once expired or revoked (McpOAuthScrubJob) and the refresh TTL is an absolute 7 days, so token rows cannot answer "has this user ever connected this client".

CREATE TABLE mcp_client_connections
(
    id                 CHAR(36)      NOT NULL,
    user_name          VARCHAR(255)  NOT NULL,
    workspace_name     VARCHAR(255)  NOT NULL,
    workspace_id       VARCHAR(255)  NOT NULL,
    client_id          CHAR(36)      NOT NULL,
    -- Denormalized from mcp_oauth_clients so the row is a self-sufficient historical record of what was
    -- connected: the client rows are keyed on a per-registration id and may be re-registered or renamed.
    client_name        VARCHAR(255)  NOT NULL,
    logo_uri           VARCHAR(2048) NULL,
    -- Which MCP resource (RFC 8707 audience) and callback the host used — distinguishes the v1/v2 resource
    -- during migration, and a loopback callback (native host) from a hosted one.
    resource           VARCHAR(2048) NOT NULL,
    redirect_uri       VARCHAR(2048) NOT NULL,
    first_connected_at TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    last_connected_at  TIMESTAMP(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),

    PRIMARY KEY (id),
    -- The claim: an upsert against this key is what decides "first connection", atomically. Keyed on
    -- workspace_id, not workspace_name: the name is mutable, so keying on it would re-fire the event on a
    -- rename and split one real workspace across two rows.
    UNIQUE KEY mcp_client_connections_identity_uk (user_name, workspace_id, client_id),
    -- Serves the future UI listing a user's connected clients, most recently used first: both equalities
    -- then the sort.
    INDEX mcp_client_connections_lookup_idx (workspace_id, user_name, last_connected_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  -- Not the utf8mb4 default (utf8mb4_0900_ai_ci): the rest of the schema is utf8mb4_unicode_ci, and mixing
  -- them makes any JOIN from this table fail with "illegal mix of collations" (see 000085).
  COLLATE = utf8mb4_unicode_ci;

--rollback DROP TABLE mcp_client_connections;
