package com.comet.opik.domain.workspaces;

import com.comet.opik.api.OpikVersion;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * Persistence service for the {@code workspaces} state-DB table.
 *
 * <p>The table is intentionally generic; this service only exposes the operations
 * required by the three current consumers (workspace version tracking, first-trace
 * analytics dedup, and the experiment project migration job). All mutations are
 * idempotent so concurrent writers across replicas are safe.</p>
 */
@ImplementedBy(WorkspacesServiceImpl.class)
public interface WorkspacesService {

    /**
     * Upserts the last known Opik version for a workspace, overwriting any prior value.
     * Intended to be invoked only after a real determination — never for allowlist
     * or feature-flag overrides.
     */
    void upsertVersion(String workspaceId, OpikVersion version, Instant determinedAt);

    /**
     * Returns the previously-recorded version for the workspace, if any.
     * Used to compute {@code version_changed} on the analytics event.
     */
    Optional<OpikVersion> findLastKnownVersion(String workspaceId);

    /**
     * Records the first-trace timestamp for a workspace using first-writer-wins semantics.
     * Returns {@code true} if this caller was the first to set the timestamp (the row
     * was created or {@code first_trace_reported_at} transitioned from NULL); {@code false}
     * if the timestamp was already set.
     */
    boolean markFirstTraceReported(String workspaceId, Instant reportedAt);

    /**
     * Marks a workspace as skipped by the experiment project migration job. Idempotent —
     * subsequent calls do not overwrite the original timestamp/reason.
     */
    void markMigrationSkipped(String workspaceId, Instant skippedAt, String reason);

    /**
     * Returns workspace IDs currently skipped by the experiment project migration job.
     * Used to assemble the per-cycle exclusion set.
     */
    List<String> findMigrationSkippedWorkspaceIds();

    /**
     * Counts workspaces currently skipped by the experiment project migration job.
     * Backs the {@code cycleTrappedWorkspaces} OpenTelemetry gauge.
     */
    long countMigrationSkipped();
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class WorkspacesServiceImpl implements WorkspacesService {

    private final @NonNull TransactionTemplate transactionTemplate;

    @Override
    public void upsertVersion(@NonNull String workspaceId, @NonNull OpikVersion version,
            @NonNull Instant determinedAt) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(WorkspacesDAO.class).upsertVersion(workspaceId, version.getValue(), determinedAt);
            return null;
        });
    }

    @Override
    public Optional<OpikVersion> findLastKnownVersion(@NonNull String workspaceId) {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(WorkspacesDAO.class)
                        .findLastKnownVersion(workspaceId)
                        .flatMap(OpikVersion::findByValue));
    }

    /**
     * Single-statement upsert + read-back-equality. Avoids the unreliable row-count semantics of
     * {@code ON DUPLICATE KEY UPDATE} ("matched" vs "changed" rows depends on the
     * {@code useAffectedRows} connector flag) and avoids the deadlock risk of a two-step
     * INSERT-then-UPDATE under high concurrency. The {@code COALESCE} keeps the earliest writer's
     * timestamp; reading the row back inside the same transaction tells us whether we were that
     * writer. Microsecond truncation is required because the column is {@code TIMESTAMP(6)} and
     * MySQL truncates nanoseconds on store, so equality must be performed at the persisted precision.
     */
    @Override
    public boolean markFirstTraceReported(@NonNull String workspaceId, @NonNull Instant reportedAt) {
        var truncatedAt = reportedAt.truncatedTo(ChronoUnit.MICROS);
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(WorkspacesDAO.class);
            dao.upsertFirstTraceReported(workspaceId, truncatedAt);
            return dao.findFirstTraceReportedAt(workspaceId)
                    .map(truncatedAt::equals)
                    .orElse(false);
        });
    }

    @Override
    public void markMigrationSkipped(@NonNull String workspaceId, @NonNull Instant skippedAt,
            @NonNull String reason) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(WorkspacesDAO.class).markMigrationSkipped(workspaceId, skippedAt, reason);
            return null;
        });
    }

    @Override
    public List<String> findMigrationSkippedWorkspaceIds() {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(WorkspacesDAO.class).findMigrationSkippedWorkspaceIds());
    }

    @Override
    public long countMigrationSkipped() {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(WorkspacesDAO.class).countMigrationSkipped());
    }
}
