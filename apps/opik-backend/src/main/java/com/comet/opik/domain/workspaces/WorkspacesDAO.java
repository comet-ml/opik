package com.comet.opik.domain.workspaces;

import com.comet.opik.api.Workspace;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@RegisterConstructorMapper(Workspace.class)
public interface WorkspacesDAO {

    @SqlQuery("SELECT * FROM workspaces WHERE id = :id")
    Optional<Workspace> findById(@Bind("id") String id);

    @SqlUpdate("""
            INSERT INTO workspaces (id, last_known_version, version_determined_at, created_by, last_updated_by)
            VALUES (:id, :version, :determinedAt, :userName, :userName)
            ON DUPLICATE KEY UPDATE
                last_known_version = :version,
                version_determined_at = :determinedAt,
                last_updated_by = :userName
            """)
    int upsertVersion(@Bind("id") String id,
            @Bind("version") String version,
            @Bind("determinedAt") Instant determinedAt,
            @Bind("userName") String userName);

    /**
     * First-writer-wins semantics on {@code first_trace_reported_at}. Returns row count:
     * {@code 1} when a new row was inserted, {@code 2} when an existing row's column transitioned
     * NULL → timestamp, {@code 0} when the column was already non-null and the upsert was a no-op
     * (Connector/J default {@code useAffectedRows=false}). Caller treats {@code rowCount > 0} as
     * "this caller was the first writer".
     */
    @SqlUpdate("""
            INSERT INTO workspaces (id, first_trace_reported_at, created_by, last_updated_by)
            VALUES (:id, :reportedAt, :userName, :userName)
            ON DUPLICATE KEY UPDATE
                last_updated_by = IF(first_trace_reported_at IS NULL, :userName, last_updated_by),
                first_trace_reported_at = COALESCE(first_trace_reported_at, :reportedAt)
            """)
    int upsertFirstTraceReported(@Bind("id") String id,
            @Bind("reportedAt") Instant reportedAt,
            @Bind("userName") String userName);

    @SqlUpdate("""
            INSERT INTO workspaces (id, migration_skipped_at, migration_skipped_reason, created_by, last_updated_by)
            VALUES (:id, :skippedAt, :reason, :userName, :userName)
            ON DUPLICATE KEY UPDATE
                last_updated_by = IF(migration_skipped_at IS NULL, :userName, last_updated_by),
                migration_skipped_at = COALESCE(migration_skipped_at, :skippedAt),
                migration_skipped_reason = COALESCE(migration_skipped_reason, :reason)
            """)
    int upsertMigrationSkipped(@Bind("id") String id,
            @Bind("skippedAt") Instant skippedAt,
            @Bind("reason") String reason,
            @Bind("userName") String userName);

    @SqlQuery("SELECT id FROM workspaces WHERE migration_skipped_at IS NOT NULL")
    List<String> findMigrationSkippedWorkspaceIds();

    @SqlQuery("SELECT COUNT(*) FROM workspaces WHERE migration_skipped_at IS NOT NULL")
    long countMigrationSkipped();
}
