--liquibase formatted sql
--changeset yaroslavb:000098_add_mcp_client_software_metadata
--comment: Persist the RFC 7591 §2 client metadata the registration endpoint was silently dropping. software_id is assigned by the client developer and is stable across installs and versions of the same product, so it identifies the MCP host itself (Claude Code, Cursor) where client_id only identifies one registration. software_version and client_uri are for display.

ALTER TABLE mcp_oauth_clients
    ADD COLUMN software_id      VARCHAR(255)  NULL AFTER name,
    ADD COLUMN software_version VARCHAR(255)  NULL AFTER software_id,
    ADD COLUMN client_uri       VARCHAR(2048) NULL AFTER logo_uri;

-- Denormalized onto the connection for the same reason as client_name: the row is a historical record of
-- what was connected, and the UI reads it without joining back to a per-registration client row.
ALTER TABLE mcp_client_connections
    ADD COLUMN software_id      VARCHAR(255) NULL AFTER client_name,
    ADD COLUMN software_version VARCHAR(255) NULL AFTER software_id;

--rollback ALTER TABLE mcp_oauth_clients DROP COLUMN software_id, DROP COLUMN software_version, DROP COLUMN client_uri;
--rollback ALTER TABLE mcp_client_connections DROP COLUMN software_id, DROP COLUMN software_version;
