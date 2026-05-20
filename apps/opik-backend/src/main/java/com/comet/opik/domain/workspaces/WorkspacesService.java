package com.comet.opik.domain.workspaces;

import com.comet.opik.api.OpikVersion;
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
    boolean markExperimentProjectMigrationSkipped(String workspaceId, String reason);

    List<String> findExperimentProjectMigrationSkippedWorkspaceIds();

    /**
     * Prompt-cycle parallel of {@link #markExperimentProjectMigrationSkipped}, recording the trap
     * in the dedicated {@code prompt_project_migration_skipped_at} column so the experiment trap
     * state isn't disturbed.
     */
    boolean markPromptProjectMigrationSkipped(String workspaceId, String reason);

    List<String> findPromptProjectMigrationSkippedWorkspaceIds();

    boolean markAutomationRuleMigrationSkipped(String workspaceId, String reason);

    List<String> findAutomationRuleMigrationSkippedWorkspaceIds();

    /**
     * Idempotent: subsequent calls do not overwrite the original timestamp/reason. Audit columns
     * are stamped with the system user — this is the dataset migration job's call site, never a
     * direct user action.
     *
     * @return {@code true} when this caller flipped the flag; {@code false} when it was already set
     */
    boolean markDatasetProjectMigrationSkipped(String workspaceId, String reason);

    List<String> findDatasetProjectMigrationSkippedWorkspaceIds();

    List<MigrationSkipReasonCount> countDatasetProjectMigrationSkippedByReason();

    /**
     * Returns whether the workspace has data in the legacy {@code feedback_scores} ClickHouse
     * table. Runs the blocking JDBI lookup on a bounded-elastic worker; defaults to {@code true}
     * when no row exists yet, and on any error so a degraded state DB doesn't break the stats
     * endpoint. The version determination flow probes the table once and persists the result
     * via {@link #upsertHasLegacyScores}.
     */
    Mono<Boolean> hasLegacyScores(String workspaceId);

    /**
     * Idempotent upsert that records the workspace's legacy-feedback-scores status explicitly.
     * Called from the workspace version determination flow after a one-shot ClickHouse presence
     * check on the {@code feedback_scores} table.
     */
    void upsertHasLegacyScores(String workspaceId, boolean hasLegacyScores, String userName);
}

@Slf4j
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
     * to the {@code experiment_project_migration_skipped_at} flag. Idempotent: returns
     * {@code false} when this caller loses the race or the workspace was already trapped.
     */
    @Override
    public boolean markExperimentProjectMigrationSkipped(@NonNull String workspaceId, @NonNull String reason) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(WorkspacesDAO.class);
            var now = Instant.now();
            if (dao.updateExperimentProjectMigrationSkippedIfNull(workspaceId, now, reason, SYSTEM_USER) > 0) {
                return true;
            }
            try {
                dao.insertExperimentProjectMigrationSkipped(workspaceId, now, reason, SYSTEM_USER);
                return true;
            } catch (UnableToExecuteStatementException exception) {
                if (exception.getCause() instanceof SQLException sql
                        && SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION.equals(sql.getSQLState())) {
                    return dao.updateExperimentProjectMigrationSkippedIfNull(workspaceId, now, reason,
                            SYSTEM_USER) > 0;
                }
                throw exception;
            }
        });
    }

    @Override
    public List<String> findExperimentProjectMigrationSkippedWorkspaceIds() {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(WorkspacesDAO.class).findExperimentProjectMigrationSkippedWorkspaceIds());
    }

    @Override
    public boolean markPromptProjectMigrationSkipped(@NonNull String workspaceId, @NonNull String reason) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(WorkspacesDAO.class);
            var now = Instant.now();
            if (dao.updatePromptProjectMigrationSkippedIfNull(workspaceId, now, reason, SYSTEM_USER) > 0) {
                return true;
            }
            try {
                dao.insertPromptProjectMigrationSkipped(workspaceId, now, reason, SYSTEM_USER);
                return true;
            } catch (UnableToExecuteStatementException exception) {
                if (exception.getCause() instanceof SQLException sql
                        && SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION.equals(sql.getSQLState())) {
                    return dao.updatePromptProjectMigrationSkippedIfNull(workspaceId, now, reason, SYSTEM_USER) > 0;
                }
                throw exception;
            }
        });
    }

    @Override
    public List<String> findPromptProjectMigrationSkippedWorkspaceIds() {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(WorkspacesDAO.class).findPromptProjectMigrationSkippedWorkspaceIds());
    }

    /**
     * Same UPDATE-then-INSERT-then-retry-UPDATE flow as {@link #markFirstTraceReported}, applied
     * to the {@code dataset_project_migration_skipped_at} flag. Idempotent: returns {@code false}
     * when this caller loses the race or the workspace was already trapped.
     */
    @Override
    public boolean markDatasetProjectMigrationSkipped(@NonNull String workspaceId, @NonNull String reason) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(WorkspacesDAO.class);
            var now = Instant.now();
            if (dao.updateDatasetProjectMigrationSkippedIfNull(workspaceId, now, reason, SYSTEM_USER) > 0) {
                return true;
            }
            try {
                dao.insertDatasetProjectMigrationSkipped(workspaceId, now, reason, SYSTEM_USER);
                return true;
            } catch (UnableToExecuteStatementException exception) {
                if (exception.getCause() instanceof SQLException sql
                        && SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION.equals(sql.getSQLState())) {
                    return dao.updateDatasetProjectMigrationSkippedIfNull(workspaceId, now, reason,
                            SYSTEM_USER) > 0;
                }
                throw exception;
            }
        });
    }

    @Override
    public List<String> findDatasetProjectMigrationSkippedWorkspaceIds() {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(WorkspacesDAO.class).findDatasetProjectMigrationSkippedWorkspaceIds());
    }

    @Override
    public List<MigrationSkipReasonCount> countDatasetProjectMigrationSkippedByReason() {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(WorkspacesDAO.class).countDatasetProjectMigrationSkippedByReason());
    }

    @Override
    public boolean markAutomationRuleMigrationSkipped(@NonNull String workspaceId, @NonNull String reason) {
        return transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(WorkspacesDAO.class);
            var now = Instant.now();
            if (dao.updateAutomationRuleMigrationSkippedIfNull(workspaceId, now, reason, SYSTEM_USER) > 0) {
                return true;
            }
            try {
                dao.insertAutomationRuleMigrationSkipped(workspaceId, now, reason, SYSTEM_USER);
                return true;
            } catch (UnableToExecuteStatementException exception) {
                if (exception.getCause() instanceof SQLException sql
                        && SQL_STATE_INTEGRITY_CONSTRAINT_VIOLATION.equals(sql.getSQLState())) {
                    return dao.updateAutomationRuleMigrationSkippedIfNull(
                            workspaceId, now, reason, SYSTEM_USER) > 0;
                }
                throw exception;
            }
        });
    }

    @Override
    public List<String> findAutomationRuleMigrationSkippedWorkspaceIds() {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(WorkspacesDAO.class).findAutomationRuleMigrationSkippedWorkspaceIds());
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

    @Override
    public void upsertHasLegacyScores(@NonNull String workspaceId, boolean hasLegacyScores,
            @NonNull String userName) {
        transactionTemplate.inTransaction(WRITE,
                handle -> handle.attach(WorkspacesDAO.class)
                        .upsertHasLegacyScores(workspaceId, hasLegacyScores, userName));
    }
}
