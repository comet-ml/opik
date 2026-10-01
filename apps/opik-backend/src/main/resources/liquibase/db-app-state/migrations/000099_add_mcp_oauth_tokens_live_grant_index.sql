--liquibase formatted sql
--changeset yaroslavb:000099_add_mcp_oauth_tokens_live_grant_index
--comment: Serve the "is there a live grant of this client_id for this user in this workspace" lookup. Three queries ask it: McpOAuthTokenDAO.existsLiveToken on the synchronous revoke path, and the correlated EXISTS in McpClientConnectionDAO.existsActiveConnectionForHost (the first-connection rule on every code exchange) and findByUser. Without it the only usable index is (user_name, workspace_name), which cannot seek past workspace_name, so each of them scans a user's whole token history. Equality columns first, then the two liveness predicates: revoked_at IS NULL is an equality on the index, expires_at > NOW(6) the closing range.

CREATE INDEX mcp_oauth_tokens_live_grant_idx
    ON mcp_oauth_tokens (user_name, workspace_id, client_id, revoked_at, expires_at);

--rollback DROP INDEX mcp_oauth_tokens_live_grant_idx ON mcp_oauth_tokens;

