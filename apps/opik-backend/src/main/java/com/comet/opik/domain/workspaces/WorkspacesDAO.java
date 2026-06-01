package com.comet.opik.domain.workspaces;

import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.time.Instant;
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
     * Atomic NULL → timestamp transition. Returns 1 when this caller flipped an existing
     * row's {@code first_trace_reported_at} from NULL to {@code :reportedAt}; returns 0 if
     * no row exists or the column was already non-null. Pair with {@link #insertFirstTrace}
     * for the missing-row case.
     */
    @SqlUpdate("""
            UPDATE workspaces
            SET first_trace_reported_at = :reportedAt,
                last_updated_by = :userName
            WHERE id = :id AND first_trace_reported_at IS NULL
            """)
    int updateFirstTraceIfNull(@Bind("id") String id,
            @Bind("reportedAt") Instant reportedAt,
            @Bind("userName") String userName);

    /**
     * Plain INSERT (no upsert). Throws on duplicate-key — caller catches and reads the
     * existing row's first-trace state to decide if it was the first writer.
     */
    @SqlUpdate("""
            INSERT INTO workspaces (id, first_trace_reported_at, created_by, last_updated_by)
            VALUES (:id, :reportedAt, :userName, :userName)
            """)
    void insertFirstTrace(@Bind("id") String id,
            @Bind("reportedAt") Instant reportedAt,
            @Bind("userName") String userName);

    /**
     * Returns the workspace's legacy-feedback-scores flag. {@code Optional.empty()} when the
     * workspace row doesn't exist yet — callers treat it as TRUE (safe-include UNION), same as
     * the column default. An admin/backfill flow flips workspaces with no legacy data to FALSE
     * so their stats queries skip the empty-table scan.
     */
    @SqlQuery("SELECT has_legacy_scores FROM workspaces WHERE id = :id")
    Optional<Boolean> findHasLegacyScores(@Bind("id") String id);

    /**
     * Idempotent upsert that sets the legacy-feedback-scores flag explicitly. Called from the
     * workspace version determination flow after a one-shot ClickHouse presence check.
     */
    @SqlUpdate("""
            INSERT INTO workspaces (id, has_legacy_scores, created_by, last_updated_by)
            VALUES (:id, :hasLegacyScores, :userName, :userName)
            ON DUPLICATE KEY UPDATE
                has_legacy_scores = :hasLegacyScores,
                last_updated_by = :userName
            """)
    int upsertHasLegacyScores(@Bind("id") String id,
            @Bind("hasLegacyScores") boolean hasLegacyScores,
            @Bind("userName") String userName);
}
