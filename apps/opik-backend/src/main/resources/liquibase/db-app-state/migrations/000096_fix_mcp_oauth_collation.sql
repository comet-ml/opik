--liquibase formatted sql
--changeset yaroslavb:000096_fix_mcp_oauth_collation
--comment: 000080 created the mcp_oauth_* tables with the utf8mb4 default collation (utf8mb4_0900_ai_ci), which
--comment: diverges from the rest of the schema (utf8mb4_unicode_ci). Any JOIN between them and a correctly
--comment: collated table fails with "illegal mix of collations" — which the connected-clients query in 000097
--comment: hits the moment it joins mcp_client_connections to mcp_oauth_tokens. Same fix as 000085.

ALTER TABLE mcp_oauth_clients CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE mcp_oauth_codes CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
ALTER TABLE mcp_oauth_tokens CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

--rollback ALTER TABLE mcp_oauth_clients CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
--rollback ALTER TABLE mcp_oauth_codes CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
--rollback ALTER TABLE mcp_oauth_tokens CONVERT TO CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
