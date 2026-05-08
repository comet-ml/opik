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
import java.util.function.Consumer;
import java.util.function.ToIntFunction;

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

    /**
     * Atomically capture the prior {@code last_known_version} (with a row-level lock) and upsert
     * the new value in a single transaction. Concurrent callers serialize per workspace, so each
     * one observes the previous writer's commit — preventing duplicate {@code version_changed=true}
     * emissions when two determinations race for the same workspace.
     *
     * @return the prior {@link OpikVersion} (empty when no row existed or the column was null)
     */
    Optional<OpikVersion> upsertVersionAndReturnPrevious(String workspaceId, OpikVersion version, String userName);

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
    public Optional<OpikVersion> upsertVersionAndReturnPrevious(@NonNull String workspaceId,
            @NonNull OpikVersion version, @NonNull String userName) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(WorkspacesDAO.class);
            var previous = dao.findVersionForUpdate(workspaceId)
                    .flatMap(OpikVersion::findByValue);
            dao.upsertVersion(workspaceId, version.getValue(), Instant.now(), userName);
            return previous;
        });
    }

    @Override
    public Optional<Workspace> findById(@NonNull String workspaceId) {
        if (StringUtils.isBlank(workspaceId)) {
            return Optional.empty();
        }
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(WorkspacesDAO.class).findById(workspaceId));
    }

    @Override
    public boolean markFirstTraceReported(@NonNull String workspaceId, @NonNull String userName) {
        var now = Instant.now();
        return transitionFlagAtomically(
                dao -> dao.updateFirstTraceIfNull(workspaceId, now, userName),
                dao -> dao.insertFirstTrace(workspaceId, now, userName));
    }

    @Override
    public boolean markMigrationSkipped(@NonNull String workspaceId, @NonNull String reason) {
        var now = Instant.now();
        return transitionFlagAtomically(
                dao -> dao.updateMigrationSkippedIfNull(workspaceId, now, reason, SYSTEM_USER),
                dao -> dao.insertMigrationSkipped(workspaceId, now, reason, SYSTEM_USER));
    }

    /**
     * UPDATE-then-INSERT, single transaction. We can't use a single-statement upsert with a
     * ROW_COUNT check here because Connector/J defaults to {@code useAffectedRows=false}
     * ({@code CLIENT_FOUND_ROWS=on}), which makes a matched-but-unchanged upsert return
     * {@code 1} — indistinguishable from a fresh insert. Splitting into two primitives keeps
     * the detection unambiguous: the UPDATE's row count is exact.
     *
     * <p>A duplicate-key on the INSERT does <b>not</b> mean another writer flipped <i>this</i>
     * flag — it just means the row exists. It might have been inserted by an unrelated writer
     * (version determination, migration job) that didn't touch the target column. We retry the
     * UPDATE-if-null to disambiguate: if the column is still NULL we flip it (and return true),
     * otherwise some prior caller already set it (return false).</p>
     *
     * @return {@code true} when this caller flipped the flag; {@code false} when another caller
     *         already set it
     */
    private boolean transitionFlagAtomically(ToIntFunction<WorkspacesDAO> updateIfNull,
            Consumer<WorkspacesDAO> insert) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(WorkspacesDAO.class);
            if (updateIfNull.applyAsInt(dao) > 0) {
                return true;
            }
            try {
                insert.accept(dao);
                return true;
            } catch (UnableToExecuteStatementException exception) {
                if (isDuplicateKeyViolation(exception)) {
                    return updateIfNull.applyAsInt(dao) > 0;
                }
                throw exception;
            }
        });
    }

    private static boolean isDuplicateKeyViolation(UnableToExecuteStatementException exception) {
        return exception.getCause() instanceof SQLException sql
                && SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION.equals(sql.getSQLState());
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
