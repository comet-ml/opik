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

@ImplementedBy(WorkspacesServiceImpl.class)
public interface WorkspacesService {

    /** Must only be called after a real determination — not on allowlist / forced-version overrides. */
    void upsertVersion(String workspaceId, OpikVersion version, Instant determinedAt);

    /** Stored values not matching the current {@link OpikVersion} enum are treated as empty. */
    Optional<OpikVersion> findLastKnownVersion(String workspaceId);

    /** Returns {@code true} only for the writer that transitioned {@code first_trace_reported_at} from NULL. */
    boolean markFirstTraceReported(String workspaceId, Instant reportedAt);

    /** Idempotent: subsequent calls do not overwrite the original timestamp/reason. */
    void markMigrationSkipped(String workspaceId, Instant skippedAt, String reason);

    List<String> findMigrationSkippedWorkspaceIds();

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
     * Microsecond truncation is required: the column is {@code TIMESTAMP(6)}, so the
     * read-back equality must be performed at the persisted precision.
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
            handle.attach(WorkspacesDAO.class).upsertMigrationSkipped(workspaceId, skippedAt, reason);
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
