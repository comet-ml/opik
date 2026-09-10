package com.comet.opik.domain.mcpoauth;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;

@RegisterConstructorMapper(McpClientConnection.class)
interface McpClientConnectionDAO {

    /**
     * Records (or refreshes) this user's connection of this client_id in this workspace, in one statement.
     * The decision whether the exchange is a new connection is made by
     * {@link #existsActiveConnectionForHost} before this runs; the return value is not consulted.
     * <p>
     * Display metadata is refreshed on every connection so a renamed or re-registered client does not leave
     * a stale row, and {@code last_connected_at} moves so a connected-clients UI can order by recency.
     */
    @SqlUpdate("""
            INSERT INTO mcp_client_connections (id, user_name, workspace_name, workspace_id, client_id,
                client_name, software_id, software_version, logo_uri, resource, redirect_uri)
            VALUES (:bean.id, :bean.userName, :bean.workspaceName, :bean.workspaceId, :bean.clientId,
                :bean.clientName, :bean.softwareId, :bean.softwareVersion, :bean.logoUri, :bean.resource,
                :bean.redirectUri)
            ON DUPLICATE KEY UPDATE
                last_connected_at = NOW(6),
                workspace_name = VALUES(workspace_name),
                client_name = VALUES(client_name),
                software_id = VALUES(software_id),
                software_version = VALUES(software_version),
                logo_uri = VALUES(logo_uri),
                resource = VALUES(resource),
                redirect_uri = VALUES(redirect_uri)
            """)
    int upsert(@BindMethods("bean") McpClientConnection connection);

    /**
     * Whether this user has a live connection from this host product in this workspace, under any client_id.
     * This is the sole test for "new connection". It deliberately ignores the client_id: hosts do not hold
     * one registration per install — Codex keeps one per project, Cursor re-registered on every reconnect
     * for months — so keying on client_id would count one adoption several times, while a host that reuses
     * its registration for weeks (Claude Code) would never count again after the user dropped and
     * re-adopted it. "Live" means backed by an unexpired, unrevoked token, the same definition
     * {@link #findByUser} reports as {@code active}.
     */
    @SqlQuery("""
            SELECT EXISTS (
                SELECT 1 FROM mcp_client_connections c
                WHERE c.user_name = :userName
                  AND c.workspace_id = :workspaceId
                  AND c.client_name = :clientName
                  AND EXISTS (
                      SELECT 1 FROM mcp_oauth_tokens t
                      WHERE t.user_name = c.user_name
                        AND t.workspace_id = c.workspace_id
                        AND t.client_id = c.client_id
                        AND t.revoked_at IS NULL
                        AND t.expires_at > NOW(6)
                  )
            )
            """)
    boolean existsActiveConnectionForHost(@Bind("userName") String userName,
            @Bind("workspaceId") String workspaceId, @Bind("clientName") String clientName);

    /**
     * Drops the connection when the grant behind it is revoked. The row is the record of a live connection,
     * not an audit log — the history lives in the warehouse, on the opik_mcp_connected events — so removing
     * it is also what makes a later re-authorization count as a new connection again.
     */
    @SqlUpdate("""
            DELETE FROM mcp_client_connections
            WHERE user_name = :userName AND workspace_id = :workspaceId AND client_id = :clientId
            """)
    int delete(@Bind("userName") String userName, @Bind("workspaceId") String workspaceId,
            @Bind("clientId") String clientId);

    /**
     * Backs a workspace's connected-clients view, most recently used first.
     * <p>
     * {@code active} is derived from the tokens rather than stored, because most disconnections are never
     * reported: a client removed on the user's machine simply stops coming back, and only the ageing out of
     * its tokens reveals it. An explicit revocation deletes the row outright; this covers the silent case.
     */
    @SqlQuery("""
            SELECT c.*, EXISTS (
                SELECT 1 FROM mcp_oauth_tokens t
                WHERE t.user_name = c.user_name
                  AND t.workspace_id = c.workspace_id
                  AND t.client_id = c.client_id
                  AND t.revoked_at IS NULL
                  AND t.expires_at > NOW(6)
            ) AS active
            FROM mcp_client_connections c
            WHERE c.workspace_id = :workspaceId AND c.user_name = :userName
            ORDER BY c.last_connected_at DESC
            """)
    List<McpClientConnection> findByUser(@Bind("workspaceId") String workspaceId,
            @Bind("userName") String userName);
}
