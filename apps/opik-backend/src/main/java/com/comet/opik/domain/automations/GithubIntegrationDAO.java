package com.comet.opik.domain.automations;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.Optional;

/** One encrypted GitHub token per workspace, for the {@code github.check} step. */
@RegisterConstructorMapper(GithubIntegrationDAO.Row.class)
public interface GithubIntegrationDAO {

    record Row(String workspaceId, String token, Instant lastUpdatedAt, String lastUpdatedBy) {
    }

    @SqlUpdate("""
            INSERT INTO github_integrations (workspace_id, token, created_by, last_updated_by)
            VALUES (:workspaceId, :token, :userName, :userName)
            ON DUPLICATE KEY UPDATE token = :token, last_updated_by = :userName
            """)
    void save(@Bind("workspaceId") String workspaceId, @Bind("token") String token, @Bind("userName") String userName);

    @SqlQuery("SELECT workspace_id, token, last_updated_at, last_updated_by FROM github_integrations WHERE workspace_id = :workspaceId")
    Optional<Row> find(@Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM github_integrations WHERE workspace_id = :workspaceId")
    void delete(@Bind("workspaceId") String workspaceId);
}
