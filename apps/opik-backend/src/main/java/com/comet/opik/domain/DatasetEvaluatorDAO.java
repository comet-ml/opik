package com.comet.opik.domain;

import com.comet.opik.api.DatasetEvaluator;
import com.comet.opik.infrastructure.db.JsonNodeArgumentFactory;
import com.comet.opik.infrastructure.db.JsonNodeColumnMapper;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterArgumentFactory(JsonNodeArgumentFactory.class)
@RegisterColumnMapper(JsonNodeColumnMapper.class)
@RegisterConstructorMapper(DatasetEvaluator.class)
public interface DatasetEvaluatorDAO {

    @SqlBatch("INSERT INTO dataset_evaluators(id, workspace_id, dataset_id, name, metric_type, metric_config, created_by, last_updated_by) "
            + "VALUES (:bean.id, :workspaceId, :bean.datasetId, :bean.name, :bean.metricType, :bean.metricConfig, :bean.createdBy, :bean.lastUpdatedBy)")
    void batchInsert(@BindMethods("bean") List<DatasetEvaluator> evaluators, @Bind("workspaceId") String workspaceId);

    @SqlQuery("SELECT * FROM dataset_evaluators WHERE workspace_id = :workspaceId AND dataset_id = :datasetId ORDER BY created_at DESC LIMIT :limit OFFSET :offset")
    List<DatasetEvaluator> findByDatasetId(@Bind("workspaceId") String workspaceId,
            @Bind("datasetId") UUID datasetId,
            @Bind("limit") int limit,
            @Bind("offset") int offset);

    @SqlQuery("SELECT COUNT(*) FROM dataset_evaluators WHERE workspace_id = :workspaceId AND dataset_id = :datasetId")
    long countByDatasetId(@Bind("workspaceId") String workspaceId, @Bind("datasetId") UUID datasetId);

    @SqlQuery("SELECT * FROM dataset_evaluators WHERE workspace_id = :workspaceId AND id IN (<ids>)")
    List<DatasetEvaluator> findByIds(@Bind("workspaceId") String workspaceId,
            @BindList("ids") Set<UUID> ids);

    @SqlUpdate("DELETE FROM dataset_evaluators WHERE workspace_id = :workspaceId AND dataset_id = :datasetId AND id IN (<ids>)")
    int deleteByIdsAndDatasetId(@Bind("workspaceId") String workspaceId,
            @Bind("datasetId") UUID datasetId,
            @BindList("ids") Set<UUID> ids);

    @SqlQuery("SELECT COUNT(*) FROM dataset_evaluators WHERE workspace_id = :workspaceId AND dataset_id = :datasetId AND id IN (<ids>)")
    long countByIdsAndDatasetId(@Bind("workspaceId") String workspaceId,
            @Bind("datasetId") UUID datasetId,
            @BindList("ids") Set<UUID> ids);

    @SqlUpdate("DELETE FROM dataset_evaluators WHERE workspace_id = :workspaceId AND dataset_id = :datasetId")
    int deleteByDatasetId(@Bind("workspaceId") String workspaceId, @Bind("datasetId") UUID datasetId);
}
