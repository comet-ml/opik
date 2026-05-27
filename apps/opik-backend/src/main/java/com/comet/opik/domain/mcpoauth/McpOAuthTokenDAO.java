package com.comet.opik.domain.mcpoauth;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.Optional;

@RegisterConstructorMapper(McpOAuthToken.class)
interface McpOAuthTokenDAO {

    @SqlUpdate("INSERT INTO mcp_oauth_tokens (token_hash, type, client_id, user_name, workspace_name, workspace_id, "
            +
            "resource, family_id, rotated_from, expires_at) " +
            "VALUES (:bean.tokenHash, :bean.type, :bean.clientId, :bean.userName, :bean.workspaceName, :bean.workspaceId, "
            +
            ":bean.resource, :bean.familyId, :bean.rotatedFrom, :bean.expiresAt)")
    void save(@BindMethods("bean") McpOAuthToken token);

    @SqlQuery("SELECT * FROM mcp_oauth_tokens WHERE token_hash = :tokenHash")
    McpOAuthToken findByHash(@Bind("tokenHash") String tokenHash);

    @SqlUpdate("UPDATE mcp_oauth_tokens SET revoked_at = NOW(6), revoked_reason = :reason "
            +
            "WHERE token_hash = :tokenHash AND revoked_at IS NULL")
    int revoke(@Bind("tokenHash") String tokenHash, @Bind("reason") String reason);

    // Reuse detection: revoke the entire refresh-token lineage in one indexed write.
    @SqlUpdate("UPDATE mcp_oauth_tokens SET revoked_at = NOW(6), revoked_reason = :reason "
            +
            "WHERE family_id = :familyId AND revoked_at IS NULL")
    int revokeFamily(@Bind("familyId") String familyId, @Bind("reason") String reason);

    @SqlUpdate("DELETE FROM mcp_oauth_tokens "
            +
            "WHERE (revoked_at IS NOT NULL AND revoked_at < :threshold) OR (expires_at < :threshold)")
    int deleteExpiredAndRevoked(@Bind("threshold") Instant threshold);

    default Optional<McpOAuthToken> fetch(String tokenHash) {
        return Optional.ofNullable(findByHash(tokenHash));
    }
}
