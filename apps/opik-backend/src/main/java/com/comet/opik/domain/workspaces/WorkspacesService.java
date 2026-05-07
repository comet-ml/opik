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

    /** Must only be called after a real determination — not on allowlist / forced-version overrides. */
    int upsertVersion(String workspaceId, OpikVersion version);

    /** Returns the row, or empty if not found / blank id. */
    Optional<Workspace> findById(String workspaceId);

    /** Returns {@code true} only for the writer that transitioned {@code first_trace_reported_at} from NULL. */
    boolean markFirstTraceReported(String workspaceId);

    /** Idempotent: subsequent calls do not overwrite the original timestamp/reason. */
    int markMigrationSkipped(String workspaceId, String reason);

    List<String> findMigrationSkippedWorkspaceIds();

    long countMigrationSkipped();
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class WorkspacesServiceImpl implements WorkspacesService {

    private static final String SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION = "23000";

    private final @NonNull TransactionTemplate transactionTemplate;

    @Override
    public int upsertVersion(@NonNull String workspaceId, @NonNull OpikVersion version) {
        return transactionTemplate.inTransaction(WRITE, handle -> handle.attach(WorkspacesDAO.class)
                .upsertVersion(workspaceId, version.getValue(), Instant.now(), SYSTEM_USER));
    }

    @Override
    public Optional<Workspace> findById(String workspaceId) {
        if (StringUtils.isBlank(workspaceId)) {
            return Optional.empty();
        }
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(WorkspacesDAO.class).findById(workspaceId));
    }

    /**
     * UPDATE-then-INSERT, single transaction. We can't use a single-statement upsert with
     * a ROW_COUNT check here because Connector/J defaults to {@code useAffectedRows=false}
     * ({@code CLIENT_FOUND_ROWS=on}), which makes a matched-but-unchanged upsert return
     * {@code 1} — indistinguishable from a fresh insert. Splitting into two primitives
     * keeps the detection unambiguous: the UPDATE's row count is exact, and a duplicate-key
     * exception on the INSERT means another writer beat us to it.
     */
    @Override
    public boolean markFirstTraceReported(@NonNull String workspaceId) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(WorkspacesDAO.class);
            var now = Instant.now();
            if (dao.updateFirstTraceIfNull(workspaceId, now, SYSTEM_USER) > 0) {
                return true;
            }
            try {
                dao.insertFirstTrace(workspaceId, now, SYSTEM_USER);
                return true;
            } catch (UnableToExecuteStatementException exception) {
                if (exception.getCause() instanceof SQLException sql
                        && SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION.equals(sql.getSQLState())) {
                    return false;
                }
                throw exception;
            }
        });
    }

    @Override
    public int markMigrationSkipped(@NonNull String workspaceId, @NonNull String reason) {
        return transactionTemplate.inTransaction(WRITE, handle -> handle.attach(WorkspacesDAO.class)
                .upsertMigrationSkipped(workspaceId, Instant.now(), reason, SYSTEM_USER));
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
