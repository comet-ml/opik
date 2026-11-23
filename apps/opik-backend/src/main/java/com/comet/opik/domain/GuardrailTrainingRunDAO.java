package com.comet.opik.domain;

import com.comet.opik.api.GuardrailTrainingRun;
import com.comet.opik.api.GuardrailTrainingRunStatus;
import com.comet.opik.infrastructure.db.JsonNodeArgumentFactory;
import com.comet.opik.infrastructure.db.JsonNodeColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import com.fasterxml.jackson.databind.JsonNode;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(JsonNodeArgumentFactory.class)
@RegisterColumnMapper(JsonNodeColumnMapper.class)
@RegisterConstructorMapper(GuardrailTrainingRun.class)
public interface GuardrailTrainingRunDAO {

    @SqlQuery("""
            SELECT * FROM guardrail_training_runs
            WHERE id = :id AND workspace_id = :workspaceId
            """)
    Optional<GuardrailTrainingRun> findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    // The serving and latest runs of a page of models, by the run ids read with each model.
    @SqlQuery("""
            SELECT * FROM guardrail_training_runs
            WHERE workspace_id = :workspaceId AND id IN (<ids>)
            """)
    @UseStringTemplateEngine
    List<GuardrailTrainingRun> findByIds(@BindList("ids") Collection<UUID> ids,
            @Bind("workspaceId") String workspaceId);

    // The run a check with the model is served by.
    @SqlQuery("""
            SELECT * FROM guardrail_training_runs
            WHERE model_id = :modelId AND workspace_id = :workspaceId AND status = 'COMPLETED'
            ORDER BY created_at DESC
            LIMIT 1
            """)
    Optional<GuardrailTrainingRun> findServingRun(@Bind("modelId") UUID modelId,
            @Bind("workspaceId") String workspaceId);

    @SqlQuery("""
            SELECT * FROM guardrail_training_runs
            WHERE model_id = :modelId AND workspace_id = :workspaceId AND status IN ('QUEUED', 'RUNNING')
            LIMIT 1
            """)
    Optional<GuardrailTrainingRun> findInFlight(@Bind("modelId") UUID modelId,
            @Bind("workspaceId") String workspaceId);

    // The attempt a retry retries, whose dataset it copies. No column links them: a model has at most one run in
    // flight, and a retry is inserted in the transaction that fails the attempt before it, so that attempt is the
    // model's most recent FAILED run before the retry. Empty for a first attempt.
    @SqlQuery("""
            SELECT * FROM guardrail_training_runs
            WHERE model_id = :run.modelId AND workspace_id = :run.workspaceId AND status = 'FAILED'
                AND created_at < :run.createdAt AND attempt = :run.attempt - 1
            ORDER BY created_at DESC
            LIMIT 1
            """)
    Optional<GuardrailTrainingRun> findPreviousAttempt(@BindMethods("run") GuardrailTrainingRun run);

    // The index finds the model's runs, tens at most; their order across statuses is a sort in memory.
    @SqlQuery("""
            SELECT * FROM guardrail_training_runs
            WHERE model_id = :modelId AND workspace_id = :workspaceId
            ORDER BY created_at DESC, id DESC
            LIMIT :limit OFFSET :offset
            """)
    List<GuardrailTrainingRun> findByModel(@Bind("modelId") UUID modelId, @Bind("workspaceId") String workspaceId,
            @Bind("limit") int limit, @Bind("offset") int offset);

    @SqlQuery("SELECT COUNT(*) FROM guardrail_training_runs WHERE model_id = :modelId AND workspace_id = :workspaceId")
    long countByModel(@Bind("modelId") UUID modelId, @Bind("workspaceId") String workspaceId);

    // The run worker's scan, the one query across workspaces: the runs in flight, the longest unchanged first.
    @SqlQuery("""
            SELECT * FROM guardrail_training_runs
            WHERE status IN ('QUEUED', 'RUNNING')
            ORDER BY last_updated_at
            LIMIT :limit
            """)
    List<GuardrailTrainingRun> findAllInFlight(@Bind("limit") int limit);

    @SqlQuery("""
            SELECT COUNT(*) FROM guardrail_training_runs
            WHERE status IN ('QUEUED', 'RUNNING') AND workspace_id = :workspaceId
            """)
    long countInFlight(@Bind("workspaceId") String workspaceId);

    // The ids a model's delete derives every run's S3 keys from.
    @SqlQuery("SELECT id FROM guardrail_training_runs WHERE model_id = :modelId AND workspace_id = :workspaceId")
    List<UUID> findIdsByModel(@Bind("modelId") UUID modelId, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM guardrail_training_runs WHERE model_id = :modelId AND workspace_id = :workspaceId")
    int deleteByModel(@Bind("modelId") UUID modelId, @Bind("workspaceId") String workspaceId);

    // A run starts QUEUED, before any job exists: attempt 1 for a create or a retrain, the next one for a retry, which
    // failAndQueueRetry inserts.
    @SqlUpdate("""
            INSERT INTO guardrail_training_runs(id, model_id, workspace_id, status, attempt, metadata, created_by,
                last_updated_by)
            VALUES (:id, :modelId, :workspaceId, 'QUEUED', :attempt, :metadata, :userName, :userName)
            """)
    void insert(@Bind("id") UUID id, @Bind("modelId") UUID modelId, @Bind("workspaceId") String workspaceId,
            @Bind("attempt") int attempt, @Bind("metadata") JsonNode metadata, @Bind("userName") String userName);

    // Conditional on the status the caller read: zero rows means another writer moved the run first, and the caller
    // drops its write. A write that keeps the status and only adds metadata, such as the job's stage, passes the same
    // status twice. The patch is merged into the metadata (RFC 7396), so each write adds its own sub-objects; a null
    // patch leaves it as it is.
    @SqlUpdate("""
            UPDATE guardrail_training_runs
            SET status = :newStatus, status_message = :statusMessage,
                metadata = JSON_MERGE_PATCH(metadata, COALESCE(:metadataPatch, JSON_OBJECT())),
                last_updated_by = :userName
            WHERE id = :id AND workspace_id = :workspaceId AND status = :expectedStatus
            """)
    int updateStatus(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId,
            @Bind("expectedStatus") GuardrailTrainingRunStatus expectedStatus,
            @Bind("newStatus") GuardrailTrainingRunStatus newStatus, @Bind("statusMessage") String statusMessage,
            @Bind("metadataPatch") JsonNode metadataPatch, @Bind("userName") String userName);

    /**
     * Fails the run from the status the caller read it in and queues the training's next attempt. Callers run it
     * inside one {@code TransactionTemplate} WRITE transaction, so the two writes land together or not at all. The
     * insert happens only when the update wrote a row: a repeated call finds the run FAILED, returns false and queues
     * no second attempt. The next attempt's id is generated by the caller before the transaction.
     */
    default boolean failAndQueueRetry(GuardrailTrainingRun run, String statusMessage, JsonNode metadataPatch,
            UUID nextId, JsonNode nextMetadata, String userName) {
        if (updateStatus(run.id(), run.workspaceId(), run.status(), GuardrailTrainingRunStatus.FAILED, statusMessage,
                metadataPatch, userName) == 0) {
            return false;
        }
        insert(nextId, run.modelId(), run.workspaceId(), run.attempt() + 1, nextMetadata, userName);
        return true;
    }
}
