package com.comet.opik.domain.workspaces;

import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface WorkspacesDAO {

    @SqlUpdate("""
            INSERT INTO workspaces (workspace_id, last_known_version, version_determined_at)
            VALUES (:workspaceId, :version, :determinedAt)
            ON DUPLICATE KEY UPDATE
                last_known_version = :version,
                version_determined_at = :determinedAt
            """)
    int upsertVersion(@Bind("workspaceId") String workspaceId,
            @Bind("version") String version,
            @Bind("determinedAt") Instant determinedAt);

    @SqlQuery("SELECT last_known_version FROM workspaces WHERE workspace_id = :workspaceId")
    Optional<String> findLastKnownVersion(@Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            INSERT INTO workspaces (workspace_id, first_trace_reported_at)
            VALUES (:workspaceId, :reportedAt)
            ON DUPLICATE KEY UPDATE
                first_trace_reported_at = COALESCE(first_trace_reported_at, :reportedAt)
            """)
    int upsertFirstTraceReported(@Bind("workspaceId") String workspaceId,
            @Bind("reportedAt") Instant reportedAt);

    @SqlQuery("SELECT first_trace_reported_at FROM workspaces WHERE workspace_id = :workspaceId")
    Optional<Instant> findFirstTraceReportedAt(@Bind("workspaceId") String workspaceId);

    @SqlUpdate("""
            INSERT INTO workspaces (workspace_id, migration_skipped_at, migration_skipped_reason)
            VALUES (:workspaceId, :skippedAt, :reason)
            ON DUPLICATE KEY UPDATE
                migration_skipped_at = COALESCE(migration_skipped_at, :skippedAt),
                migration_skipped_reason = COALESCE(migration_skipped_reason, :reason)
            """)
    int upsertMigrationSkipped(@Bind("workspaceId") String workspaceId,
            @Bind("skippedAt") Instant skippedAt,
            @Bind("reason") String reason);

    @SqlQuery("SELECT workspace_id FROM workspaces WHERE migration_skipped_at IS NOT NULL")
    List<String> findMigrationSkippedWorkspaceIds();

    @SqlQuery("SELECT COUNT(*) FROM workspaces WHERE migration_skipped_at IS NOT NULL")
    long countMigrationSkipped();
}
