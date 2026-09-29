--liquibase formatted sql
--changeset yaroslavb:000096_add_absolute_expires_at_to_mcp_oauth_tokens
--comment: Refresh-token lifetime became sliding (OPIK-8351): every rotation issues the new refresh token with a fresh idle
--comment: lifetime, so the absolute lifetime of a token family (authorization time + refreshTokenAbsoluteTtl) has to be
--comment: carried on the tokens themselves. It cannot be derived from the family's oldest row, which the scrub job deletes
--comment: shortly after rotation. NULL on rows minted before this change; the next rotation starts the cap from then.
ALTER TABLE mcp_oauth_tokens ADD COLUMN absolute_expires_at TIMESTAMP(6) NULL;

--rollback ALTER TABLE mcp_oauth_tokens DROP COLUMN absolute_expires_at;
