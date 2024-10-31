package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetLastExperimentCreated;
import com.comet.opik.api.DatasetUpdate;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.core.Handle;
import org.jdbi.v3.core.statement.PreparedBatch;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterConstructorMapper(Dataset.class)
public interface DatasetDAO {

    @SqlUpdate("INSERT INTO datasets(id, name, description, workspace_id, created_by, last_updated_by) " +
            "VALUES (:dataset.id, :dataset.name, :dataset.description, :workspace_id, :dataset.createdBy, :dataset.lastUpdatedBy)")
    void save(@BindMethods("dataset") Dataset dataset, @Bind("workspace_id") String workspaceId);

    @SqlUpdate("""
            UPDATE datasets SET
                name = :dataset.name,
                description = :dataset.description,
                last_updated_by = :lastUpdatedBy
            WHERE id = :id AND workspace_id = :workspace_id
            """)
    int update(@Bind("workspace_id") String workspaceId,
            @Bind("id") UUID id,
            @BindMethods("dataset") DatasetUpdate dataset,
            @Bind("lastUpdatedBy") String lastUpdatedBy);

    @SqlQuery("SELECT * FROM datasets WHERE id = :id AND workspace_id = :workspace_id")
    Optional<Dataset> findById(@Bind("id") UUID id, @Bind("workspace_id") String workspaceId);

    @SqlQuery("SELECT * FROM datasets WHERE id IN (<ids>) AND workspace_id = :workspace_id")
    List<Dataset> findByIds(@BindList("ids") Set<UUID> ids, @Bind("workspace_id") String workspaceId);

    @SqlUpdate("DELETE FROM datasets WHERE id = :id AND workspace_id = :workspace_id")
    void delete(@Bind("id") UUID id, @Bind("workspace_id") String workspaceId);

    @SqlUpdate("DELETE FROM datasets WHERE workspace_id = :workspace_id AND name = :name")
    void delete(@Bind("workspace_id") String workspaceId, @Bind("name") String name);

    @SqlQuery("SELECT COUNT(*) FROM datasets " +
            " WHERE workspace_id = :workspace_id " +
            " <if(name)> AND name like concat('%', :name, '%') <endif> " +
            " <if(with_experiments_only)> AND last_created_experiment_at IS NOT NULL <endif> ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCount(@Bind("workspace_id") String workspaceId, @Define("name") @Bind("name") String name,
            @Define("with_experiments_only") boolean withExperimentsOnly);

    @SqlQuery("SELECT * FROM datasets " +
            " WHERE workspace_id = :workspace_id " +
            " <if(name)> AND name like concat('%', :name, '%') <endif> " +
            " <if(with_experiments_only)> AND last_created_experiment_at IS NOT NULL <endif> " +
            " ORDER BY id DESC " +
            " LIMIT :limit OFFSET :offset ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<Dataset> find(@Bind("limit") int limit,
            @Bind("offset") int offset,
            @Bind("workspace_id") String workspaceId,
            @Define("name") @Bind("name") String name,
            @Define("with_experiments_only") boolean withExperimentsOnly);

    @SqlQuery("SELECT * FROM datasets WHERE workspace_id = :workspace_id AND name = :name")
    Optional<Dataset> findByName(@Bind("workspace_id") String workspaceId, @Bind("name") String name);

    default int[] recordExperiments(Handle handle, String workspaceId,
            Collection<DatasetLastExperimentCreated> datasets) {
        PreparedBatch batch = handle.prepareBatch(
                "UPDATE datasets SET last_created_experiment_at = :experimentCreatedAt WHERE id = :datasetId AND workspace_id = :workspace_id");
        for (DatasetLastExperimentCreated dataset : datasets) {
            batch.bind("workspace_id", workspaceId)
                    .bind("datasetId", dataset.datasetId())
                    .bind("experimentCreatedAt", dataset.experimentCreatedAt());

            batch.add();
        }
        return batch.execute();
    }

}
