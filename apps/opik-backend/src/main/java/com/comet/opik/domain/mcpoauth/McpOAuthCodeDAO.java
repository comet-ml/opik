package com.comet.opik.domain.mcpoauth;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.Optional;

@RegisterConstructorMapper(McpOAuthCode.class)
interface McpOAuthCodeDAO {

    @SqlUpdate("INSERT INTO mcp_oauth_codes (id, code_hash, client_id, user_name, workspace_name, workspace_id, "
            +
            "code_challenge, code_challenge_method, redirect_uri, resource, expires_at) " +
            "VALUES (:bean.id, :bean.codeHash, :bean.clientId, :bean.userName, :bean.workspaceName, :bean.workspaceId, "
            +
            ":bean.codeChallenge, :bean.codeChallengeMethod, :bean.redirectUri, :bean.resource, :bean.expiresAt)")
    void save(@BindMethods("bean") McpOAuthCode code);

    @SqlQuery("SELECT * FROM mcp_oauth_codes WHERE code_hash = :codeHash")
    McpOAuthCode findByHash(@Bind("codeHash") String codeHash);

    // Atomic single-use consume: succeeds (returns 1) only if the code is unused and unexpired. Guards
    // against double-spend on concurrent /oauth/token calls.
    @SqlUpdate("UPDATE mcp_oauth_codes SET used_at = NOW(6) "
            +
            "WHERE code_hash = :codeHash AND used_at IS NULL AND expires_at > NOW(6)")
    int markUsed(@Bind("codeHash") String codeHash);

    @SqlUpdate("DELETE FROM mcp_oauth_codes WHERE expires_at < :threshold")
    int deleteExpired(@Bind("threshold") Instant threshold);

    default Optional<McpOAuthCode> fetch(String codeHash) {
        return Optional.ofNullable(findByHash(codeHash));
    }
}
