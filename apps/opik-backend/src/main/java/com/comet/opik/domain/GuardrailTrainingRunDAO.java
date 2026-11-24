package com.comet.opik.domain;

import com.comet.opik.api.GuardrailTrainingRun;
import com.comet.opik.infrastructure.db.JsonNodeArgumentFactory;
import com.comet.opik.infrastructure.db.JsonNodeColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import com.fasterxml.jackson.databind.JsonNode;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
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

    @SqlQuery("""
            SELECT * FROM guardrail_training_runs
            WHERE model_id = :modelId AND workspace_id = :workspaceId AND status IN ('QUEUED', 'RUNNING')
            LIMIT 1
            """)
    Optional<GuardrailTrainingRun> findInFlight(@Bind("modelId") UUID modelId,
            @Bind("workspaceId") String workspaceId);

    // While a model has a few runs MySQL sorts them from the status index; as they grow it reads them in order from
    // (model_id, created_at), whose primary-key suffix serves the id tie-break.
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

    // The ids a model's delete derives every run's S3 keys from.
    @SqlQuery("SELECT id FROM guardrail_training_runs WHERE model_id = :modelId AND workspace_id = :workspaceId")
    List<UUID> findIdsByModel(@Bind("modelId") UUID modelId, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM guardrail_training_runs WHERE model_id = :modelId AND workspace_id = :workspaceId")
    int deleteByModel(@Bind("modelId") UUID modelId, @Bind("workspaceId") String workspaceId);

    // A run starts QUEUED, before any job exists: attempt 1 for a create or a retrain.
    @SqlUpdate("""
            INSERT INTO guardrail_training_runs(id, model_id, workspace_id, status, attempt, metadata, created_by,
                last_updated_by)
            VALUES (:id, :modelId, :workspaceId, 'QUEUED', :attempt, :metadata, :userName, :userName)
            """)
    void insert(@Bind("id") UUID id, @Bind("modelId") UUID modelId, @Bind("workspaceId") String workspaceId,
            @Bind("attempt") int attempt, @Bind("metadata") JsonNode metadata, @Bind("userName") String userName);

    // One write from either status in flight, so a run that moved from QUEUED to RUNNING since the caller read it is
    // still cancelled. Zero rows: the run is not the model's, is already cancelled, or has completed or failed.
    @SqlUpdate("""
            UPDATE guardrail_training_runs
            SET status = 'CANCELLED', status_message = :statusMessage, finished_at = CURRENT_TIMESTAMP(6),
                last_updated_by = :userName
            WHERE id = :id AND model_id = :modelId AND workspace_id = :workspaceId AND status IN ('QUEUED', 'RUNNING')
            """)
    int cancel(@Bind("id") UUID id, @Bind("modelId") UUID modelId, @Bind("workspaceId") String workspaceId,
            @Bind("statusMessage") String statusMessage, @Bind("userName") String userName);
}
