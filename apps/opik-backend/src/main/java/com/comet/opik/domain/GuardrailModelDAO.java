package com.comet.opik.domain;

import com.comet.opik.api.GuardrailModel;
import com.comet.opik.api.GuardrailModelConfig;
import com.comet.opik.api.GuardrailTrainingRunStatus;
import com.comet.opik.infrastructure.db.GuardrailModelConfigColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * A model's state is two correlated reads on its runs: its serving run, the most recent COMPLETED one, on the runs'
 * (model_id, status, created_at) index, and its latest run, across statuses, which MySQL sorts from that index while a
 * model has a few runs and reads in order from (model_id, created_at) as they grow. Both break a tie in created_at by
 * id, which InnoDB keeps at the end of every secondary index. The runs need no workspace check of their own: they are
 * reached through a model already checked.
 */
@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(GuardrailModelConfigColumnMapper.class)
@RegisterColumnMapper(GuardrailModelConfigColumnMapper.class)
@RegisterConstructorMapper(GuardrailModel.class)
public interface GuardrailModelDAO {

    @SqlUpdate("""
            INSERT INTO guardrail_models(id, workspace_id, name, description, config, created_by, last_updated_by)
            VALUES (:id, :workspaceId, :name, :description, :config, :userName, :userName)
            """)
    void save(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId, @Bind("name") String name,
            @Bind("description") String description, @Bind("config") GuardrailModelConfig config,
            @Bind("userName") String userName);

    @SqlQuery("""
            SELECT m.id, m.name, m.description, m.config,
                (SELECT r.id FROM guardrail_training_runs r WHERE r.model_id = m.id AND r.status = 'COMPLETED'
                    ORDER BY r.created_at DESC, r.id DESC LIMIT 1) AS serving_run_id,
                (SELECT r.id FROM guardrail_training_runs r WHERE r.model_id = m.id
                    ORDER BY r.created_at DESC, r.id DESC LIMIT 1) AS latest_run_id,
                m.created_at, m.created_by, m.last_updated_at, m.last_updated_by
            FROM guardrail_models m
            WHERE m.id = :id AND m.workspace_id = :workspaceId
            """)
    Optional<GuardrailModel> findById(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT EXISTS (SELECT 1 FROM guardrail_models WHERE id = :id AND workspace_id = :workspaceId)")
    boolean exists(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    // Holds the model row until the transaction ends, so writes that start or remove its runs run one at a time per
    // model. Plain reads do not wait for it.
    @SqlQuery("SELECT id FROM guardrail_models WHERE id = :id AND workspace_id = :workspaceId FOR UPDATE")
    Optional<UUID> lock(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    // A null description or config leaves its column as it is, and an empty description is stored as none.
    @SqlUpdate("""
            UPDATE guardrail_models
            SET <if(description)> description = NULLIF(:description, ''), <endif> <if(config)> config = :config, <endif>
                last_updated_by = :userName
            WHERE id = :id AND workspace_id = :workspaceId
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    int update(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId,
            @Define("description") @Bind("description") String description,
            @Define("config") @Bind("config") GuardrailModelConfig config, @Bind("userName") String userName);

    // name is a partial name already escaped with GuardrailPolicyDAO.escapeLike, so its '%' and '_' match only
    // themselves. usable_only keeps the models with a serving run, the ones a rule can name.
    @SqlQuery("""
            SELECT COUNT(*) FROM guardrail_models m
            WHERE m.workspace_id = :workspaceId
            <if(name)> AND m.name LIKE CONCAT('%', :name, '%') ESCAPE '!' <endif>
            <if(usable_only)> AND EXISTS (SELECT 1 FROM guardrail_training_runs r
                WHERE r.model_id = m.id AND r.status = 'COMPLETED') <endif>
            <if(latest_run_status)> AND (SELECT r.status FROM guardrail_training_runs r WHERE r.model_id = m.id
                ORDER BY r.created_at DESC, r.id DESC LIMIT 1) = :latestRunStatus <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long count(@Bind("workspaceId") String workspaceId, @Define("name") @Bind("name") String name,
            @Define("usable_only") boolean usableOnly,
            @Define("latest_run_status") @Bind("latestRunStatus") GuardrailTrainingRunStatus latestRunStatus);

    /**
     * The ids of one page, in order, with the filters of {@link #count}. Its runs are read for the page's rows only, by
     * {@link #findByIds}, as in the two-step page fetch of the policies.
     */
    @SqlQuery("""
            SELECT m.id FROM guardrail_models m
            WHERE m.workspace_id = :workspaceId
            <if(name)> AND m.name LIKE CONCAT('%', :name, '%') ESCAPE '!' <endif>
            <if(usable_only)> AND EXISTS (SELECT 1 FROM guardrail_training_runs r
                WHERE r.model_id = m.id AND r.status = 'COMPLETED') <endif>
            <if(latest_run_status)> AND (SELECT r.status FROM guardrail_training_runs r WHERE r.model_id = m.id
                ORDER BY r.created_at DESC, r.id DESC LIMIT 1) = :latestRunStatus <endif>
            ORDER BY <if(sort_fields)> <sort_fields>, <endif> m.id DESC
            LIMIT :limit OFFSET :offset
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<UUID> findPageIds(@Bind("workspaceId") String workspaceId, @Define("name") @Bind("name") String name,
            @Define("usable_only") boolean usableOnly,
            @Define("latest_run_status") @Bind("latestRunStatus") GuardrailTrainingRunStatus latestRunStatus,
            @Define("sort_fields") String sortFields, @Bind("limit") int limit, @Bind("offset") int offset);

    @SqlQuery("""
            SELECT m.id, m.name, m.description, m.config,
                (SELECT r.id FROM guardrail_training_runs r WHERE r.model_id = m.id AND r.status = 'COMPLETED'
                    ORDER BY r.created_at DESC, r.id DESC LIMIT 1) AS serving_run_id,
                (SELECT r.id FROM guardrail_training_runs r WHERE r.model_id = m.id
                    ORDER BY r.created_at DESC, r.id DESC LIMIT 1) AS latest_run_id,
                m.created_at, m.created_by, m.last_updated_at, m.last_updated_by
            FROM guardrail_models m
            WHERE m.workspace_id = :workspaceId AND m.id IN (<ids>)
            """)
    @UseStringTemplateEngine
    List<GuardrailModel> findByIds(@BindList("ids") Collection<UUID> ids, @Bind("workspaceId") String workspaceId);

    // The delete guard. A rule names its model only inside its config JSON, so this reads the CUSTOM_CLASSIFIER rules
    // of every policy in the workspace: few policies, each with at most one rule per type. A generated model_id column
    // on the rules, with an index, is one migration if that ever shows.
    @SqlQuery("""
            SELECT EXISTS (
                SELECT 1 FROM guardrail_policies p
                JOIN guardrail_policy_rules r ON r.policy_id = p.id AND r.type = 'CUSTOM_CLASSIFIER'
                WHERE p.workspace_id = :workspaceId AND r.config ->> '$.model_id' = :id
            )
            """)
    boolean isUsedByCustomClassifierRule(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);

    @SqlUpdate("DELETE FROM guardrail_models WHERE id = :id AND workspace_id = :workspaceId")
    int delete(@Bind("id") UUID id, @Bind("workspaceId") String workspaceId);
}
