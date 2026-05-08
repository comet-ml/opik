package com.comet.opik.domain.workspaces;

import com.comet.opik.api.OpikVersion;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.comet.opik.infrastructure.auth.RequestContext.SYSTEM_USER;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(WorkspacesServiceImpl.class)
public interface WorkspacesService {

    /**
     * Must only be called after a real determination — not on allowlist / forced-version overrides.
     * {@code userName} is recorded in the audit columns (caller passes the API user from the request
     * context).
     */
    int upsertVersion(String workspaceId, OpikVersion version, String userName);

    /** Returns the row, or empty if not found / blank id. */
    Optional<Workspace> findById(@NonNull String workspaceId);

    /**
     * Returns {@code true} only for the writer that transitioned {@code first_trace_reported_at}
     * from NULL. {@code userName} is recorded in the audit columns (caller passes the user that
     * created the trace).
     */
    boolean markFirstTraceReported(String workspaceId, String userName);

    /**
     * Idempotent: subsequent calls do not overwrite the original timestamp/reason. Audit columns
     * are stamped with the system user — this is the migration job's call site, never a direct
     * user action.
     *
     * @return {@code true} when this caller flipped the flag; {@code false} when it was already set
     */
    boolean markMigrationSkipped(String workspaceId, String reason);

    List<String> findMigrationSkippedWorkspaceIds();

    long countMigrationSkipped();
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class WorkspacesServiceImpl implements WorkspacesService {

    private static final String SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION = "23000";

    private final @NonNull TransactionTemplate transactionTemplate;

    @Override
    public int upsertVersion(@NonNull String workspaceId, @NonNull OpikVersion version, @NonNull String userName) {
        return transactionTemplate.inTransaction(WRITE, handle -> handle.attach(WorkspacesDAO.class)
                .upsertVersion(workspaceId, version.getValue(), Instant.now(), userName));
    }

    @Override
    public Optional<Workspace> findById(@NonNull String workspaceId) {
        if (StringUtils.isBlank(workspaceId)) {
            return Optional.empty();
        }
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(WorkspacesDAO.class).findById(workspaceId));
    }

    /**
     * UPDATE-then-INSERT, single transaction. We can't use a single-statement upsert with a
     * ROW_COUNT check here because Connector/J defaults to {@code useAffectedRows=false}
     * ({@code CLIENT_FOUND_ROWS=on}), which makes a matched-but-unchanged upsert return
     * {@code 1} — indistinguishable from a fresh insert. Splitting into two primitives keeps the
     * detection unambiguous.
     *
     * <p>A duplicate-key on the INSERT does <b>not</b> mean another writer flipped
     * {@code first_trace_reported_at} — it just means the row exists. It might have been inserted
     * by an unrelated writer (version determination, migration job) that didn't touch the column.
     * Retrying the UPDATE-if-null disambiguates: if the column is still NULL we flip it.</p>
     */
    @Override
    public boolean markFirstTraceReported(@NonNull String workspaceId, @NonNull String userName) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(WorkspacesDAO.class);
            var now = Instant.now();
            if (dao.updateFirstTraceIfNull(workspaceId, now, userName) > 0) {
                return true;
            }
            try {
                dao.insertFirstTrace(workspaceId, now, userName);
                return true;
            } catch (UnableToExecuteStatementException exception) {
                if (exception.getCause() instanceof SQLException sql
                        && SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION.equals(sql.getSQLState())) {
                    return dao.updateFirstTraceIfNull(workspaceId, now, userName) > 0;
                }
                throw exception;
            }
        });
    }

    /**
     * Same UPDATE-then-INSERT-then-retry-UPDATE flow as {@link #markFirstTraceReported}, applied
     * to the {@code migration_skipped_at} flag. Idempotent: returns {@code false} when this caller
     * loses the race or the workspace was already trapped.
     */
    @Override
    public boolean markMigrationSkipped(@NonNull String workspaceId, @NonNull String reason) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(WorkspacesDAO.class);
            var now = Instant.now();
            if (dao.updateMigrationSkippedIfNull(workspaceId, now, reason, SYSTEM_USER) > 0) {
                return true;
            }
            try {
                dao.insertMigrationSkipped(workspaceId, now, reason, SYSTEM_USER);
                return true;
            } catch (UnableToExecuteStatementException exception) {
                if (exception.getCause() instanceof SQLException sql
                        && SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION.equals(sql.getSQLState())) {
                    return dao.updateMigrationSkippedIfNull(workspaceId, now, reason, SYSTEM_USER) > 0;
                }
                throw exception;
            }
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
