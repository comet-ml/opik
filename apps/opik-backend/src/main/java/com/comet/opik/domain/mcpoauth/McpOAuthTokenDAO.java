package com.comet.opik.domain.mcpoauth;

import com.comet.opik.infrastructure.db.RevokedReasonMapper;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.time.Instant;
import java.util.Optional;

@RegisterConstructorMapper(McpOAuthToken.class)
@RegisterArgumentFactory(RevokedReasonMapper.class)
@RegisterColumnMapper(RevokedReasonMapper.class)
interface McpOAuthTokenDAO {

    @SqlUpdate("""
            INSERT INTO mcp_oauth_tokens (id, token_hash, type, client_id, user_name, workspace_name, workspace_id,
                resource, family_id, rotated_from_id, expires_at)
            VALUES (:bean.id, :bean.tokenHash, :bean.type, :bean.clientId, :bean.userName, :bean.workspaceName, :bean.workspaceId,
                :bean.resource, :bean.familyId, :bean.rotatedFromId, :bean.expiresAt)
            """)
    void save(@BindMethods("bean") McpOAuthToken token);

    @SqlQuery("SELECT * FROM mcp_oauth_tokens WHERE token_hash = :tokenHash")
    McpOAuthToken findByHash(@Bind("tokenHash") String tokenHash);

    @UseStringTemplateEngine
    @SqlUpdate("""
            UPDATE mcp_oauth_tokens SET revoked_at = NOW(6), revoked_reason = :reason
            WHERE <column> = :value AND revoked_at IS NULL
            """)
    int revokeBy(@Define("column") String column, @Bind("value") String value, @Bind("reason") RevokedReason reason);

    default int revoke(String tokenHash, RevokedReason reason) {
        return revokeBy("token_hash", tokenHash, reason);
    }

    default int revokeFamily(String familyId, RevokedReason reason) {
        return revokeBy("family_id", familyId, reason);
    }

    @SqlUpdate("""
            DELETE FROM mcp_oauth_tokens
            WHERE (revoked_at IS NOT NULL AND revoked_at < :threshold) OR (expires_at < :threshold)
            """)
    int deleteExpiredAndRevoked(@Bind("threshold") Instant threshold);

    default Optional<McpOAuthToken> fetch(String tokenHash) {
        return Optional.ofNullable(findByHash(tokenHash));
    }
}
