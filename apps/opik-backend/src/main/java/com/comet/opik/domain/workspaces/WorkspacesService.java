package com.comet.opik.domain.workspaces;

import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLException;
import java.time.Instant;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(WorkspacesServiceImpl.class)
public interface WorkspacesService {

    /**
     * Returns {@code true} only for the writer that transitioned {@code first_trace_reported_at}
     * from NULL. {@code userName} is recorded in the audit columns (caller passes the user that
     * created the trace).
     */
    boolean markFirstTraceReported(String workspaceId, String userName);

    /**
     * Returns whether the workspace has data in the legacy {@code feedback_scores} ClickHouse
     * table, read from the persisted {@code has_legacy_scores} column. Runs the blocking JDBI
     * lookup on a bounded-elastic worker; defaults to {@code true} when no row exists yet, and on
     * any error so a degraded state DB doesn't break the stats endpoint. Consumed by the trace/span
     * stats queries to decide whether to UNION the legacy {@code feedback_scores} table.
     */
    Mono<Boolean> hasLegacyScores(String workspaceId);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class WorkspacesServiceImpl implements WorkspacesService {

    private static final String SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION = "23000";

    private final @NonNull TransactionTemplate transactionTemplate;

    /**
     * UPDATE-then-INSERT, single transaction. We can't use a single-statement upsert with a
     * ROW_COUNT check here because Connector/J defaults to {@code useAffectedRows=false}
     * ({@code CLIENT_FOUND_ROWS=on}), which makes a matched-but-unchanged upsert return
     * {@code 1} — indistinguishable from a fresh insert. Splitting into two primitives keeps the
     * detection unambiguous.
     *
     * <p>A duplicate-key on the INSERT means a concurrent first-trace writer created the row
     * between our UPDATE and INSERT (this is the only path that inserts into {@code workspaces}).
     * Retrying the UPDATE-if-null then flips the column only if it is still NULL; since the other
     * writer already set it, this caller returns {@code false}, so exactly one caller reports the
     * first trace.</p>
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

    @Override
    public Mono<Boolean> hasLegacyScores(@NonNull String workspaceId) {
        if (StringUtils.isBlank(workspaceId)) {
            return Mono.just(true);
        }
        return Mono.fromCallable(() -> transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(WorkspacesDAO.class).findHasLegacyScores(workspaceId))
                .orElse(true))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(throwable -> {
                    log.warn("Failed to resolve has_legacy_scores for workspace '{}', defaulting to true",
                            workspaceId, throwable);
                    return Mono.just(true);
                });
    }
}
