package com.comet.opik.domain;

import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetLastExperimentCreated;
import com.comet.opik.api.DatasetLastOptimizationCreated;
import com.comet.opik.api.DatasetUpdate;
import com.comet.opik.api.Visibility;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
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
@RegisterConstructorMapper(BiInformationResponse.BiInformation.class)
public interface DatasetDAO {

    @SqlUpdate("INSERT INTO datasets(id, name, description, visibility, workspace_id, created_by, last_updated_by) " +
            "VALUES (:dataset.id, :dataset.name, :dataset.description, COALESCE(:dataset.visibility, 'private'), :workspace_id, :dataset.createdBy, :dataset.lastUpdatedBy)")
    void save(@BindMethods("dataset") Dataset dataset, @Bind("workspace_id") String workspaceId);

    @SqlUpdate("""
            UPDATE datasets SET
                name = :dataset.name,
                description = :dataset.description,
                visibility = COALESCE(:dataset.visibility, visibility),
                last_updated_by = :lastUpdatedBy
            WHERE id = :id AND workspace_id = :workspace_id
            """)
    int update(@Bind("workspace_id") String workspaceId,
            @Bind("id") UUID id,
            @BindMethods("dataset") DatasetUpdate dataset,
            @Bind("lastUpdatedBy") String lastUpdatedBy);

    @SqlQuery("SELECT * FROM datasets WHERE id = :id AND workspace_id = :workspace_id")
    Optional<Dataset> findById(@Bind("id") UUID id, @Bind("workspace_id") String workspaceId);

    @SqlQuery("SELECT workspace_id FROM datasets WHERE id = :id")
    Optional<String> findWorkspaceIdByDatasetId(@Bind("id") UUID id);

    @SqlQuery("SELECT * FROM datasets WHERE id IN (<ids>) AND workspace_id = :workspace_id")
    List<Dataset> findByIds(@BindList("ids") Set<UUID> ids, @Bind("workspace_id") String workspaceId);

    @SqlUpdate("DELETE FROM datasets WHERE id = :id AND workspace_id = :workspace_id")
    void delete(@Bind("id") UUID id, @Bind("workspace_id") String workspaceId);

    @SqlUpdate("DELETE FROM datasets WHERE workspace_id = :workspace_id AND name = :name")
    void delete(@Bind("workspace_id") String workspaceId, @Bind("name") String name);

    @SqlUpdate("DELETE FROM datasets WHERE id IN (<ids>) AND workspace_id = :workspace_id")
    void delete(@BindList("ids") Set<UUID> ids, @Bind("workspace_id") String workspaceId);

    @SqlQuery("SELECT COUNT(id) FROM datasets " +
            " WHERE workspace_id = :workspace_id " +
            " <if(name)> AND name like concat('%', :name, '%') <endif> " +
            " <if(visibility)> AND visibility = :visibility <endif> " +
            " <if(with_experiments_only)> AND last_created_experiment_at IS NOT NULL <endif> " +
            " <if(with_optimizations_only)> AND last_created_optimization_at IS NOT NULL <endif> ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCount(@Bind("workspace_id") String workspaceId, @Define("name") @Bind("name") String name,
            @Define("with_experiments_only") boolean withExperimentsOnly,
            @Define("with_optimizations_only") boolean withOptimizationOnly,
            @Define("visibility") @Bind("visibility") Visibility visibility);

    @SqlQuery("SELECT COUNT(id) FROM datasets " +
            "WHERE workspace_id = :workspace_id " +
            "AND id IN (<ids>) " +
            "<if(name)> AND name like concat('%', :name, '%') <endif> " +
            "<if(visibility)> AND visibility = :visibility <endif> ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCountByIds(@Bind("workspace_id") String workspaceId, @BindList("ids") Set<UUID> ids,
            @Define("name") @Bind("name") String name,
            @Define("visibility") @Bind("visibility") Visibility visibility);

    @SqlQuery("SELECT * FROM datasets " +
            "WHERE workspace_id = :workspace_id " +
            "AND id IN (<ids>) " +
            "<if(name)> AND name like concat('%', :name, '%') <endif> " +
            "<if(visibility)> AND visibility = :visibility <endif> " +
            " ORDER BY <if(sort_fields)> <sort_fields>, <endif> id DESC " +
            " LIMIT :limit OFFSET :offset ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<Dataset> findByIds(@Bind("workspace_id") String workspaceId, @BindList("ids") Set<UUID> ids,
            @Define("name") @Bind("name") String name, @Bind("limit") int limit, @Bind("offset") int offset,
            @Define("sort_fields") @Bind("sort_fields") String sortingFields,
            @Define("visibility") @Bind("visibility") Visibility visibility);

    @SqlQuery("SELECT COUNT(id) FROM datasets " +
            "WHERE workspace_id = :workspace_id " +
            "AND id IN (SELECT id FROM experiment_dataset_ids_<table_name>) " +
            "<if(name)> AND name like concat('%', :name, '%') <endif> " +
            "<if(visibility)> AND visibility = :visibility <endif> ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCountByTempTable(@Bind("workspace_id") String workspaceId, @Define("table_name") String tableName,
            @Define("name") @Bind("name") String name,
            @Define("visibility") @Bind("visibility") Visibility visibility);

    @SqlQuery("SELECT * FROM datasets " +
            "WHERE workspace_id = :workspace_id " +
            "AND id IN (SELECT id FROM experiment_dataset_ids_<table_name>) " +
            "<if(name)> AND name like concat('%', :name, '%') <endif> " +
            "<if(visibility)> AND visibility = :visibility <endif> " +
            " ORDER BY <if(sort_fields)> <sort_fields>, <endif> id DESC " +
            " LIMIT :limit OFFSET :offset ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<Dataset> findByTempTable(@Bind("workspace_id") String workspaceId, @Define("table_name") String tableName,
            @Define("name") @Bind("name") String name, @Bind("limit") int limit, @Bind("offset") int offset,
            @Define("sort_fields") @Bind("sort_fields") String sortingFields,
            @Define("visibility") @Bind("visibility") Visibility visibility);

    @SqlQuery("SELECT * FROM datasets " +
            " WHERE workspace_id = :workspace_id " +
            " <if(name)> AND name like concat('%', :name, '%') <endif> " +
            " <if(visibility)> AND visibility = :visibility <endif> " +
            " <if(with_experiments_only)> AND last_created_experiment_at IS NOT NULL <endif> " +
            " <if(with_optimizations_only)> AND last_created_optimization_at IS NOT NULL <endif> " +
            " ORDER BY <if(sort_fields)> <sort_fields>, <endif> id DESC " +
            " LIMIT :limit OFFSET :offset ")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<Dataset> find(@Bind("limit") int limit,
            @Bind("offset") int offset,
            @Bind("workspace_id") String workspaceId,
            @Define("name") @Bind("name") String name,
            @Define("with_experiments_only") boolean withExperimentsOnly,
            @Define("with_optimizations_only") boolean withOptimizationOnly,
            @Define("sort_fields") @Bind("sort_fields") String sortingFields,
            @Define("visibility") @Bind("visibility") Visibility visibility);

    @SqlQuery("SELECT * FROM datasets WHERE workspace_id = :workspace_id AND name = :name")
    Optional<Dataset> findByName(@Bind("workspace_id") String workspaceId, @Bind("name") String name);

    @SqlBatch("UPDATE datasets SET last_created_experiment_at = :experimentCreatedAt WHERE id = :datasetId AND workspace_id = :workspace_id")
    int[] recordExperiments(@Bind("workspace_id") String workspaceId,
            @BindMethods Collection<DatasetLastExperimentCreated> datasets);

    @SqlBatch("UPDATE datasets SET last_created_optimization_at = :optimizationCreatedAt WHERE id = :datasetId AND workspace_id = :workspace_id")
    int[] recordOptimizations(@Bind("workspace_id") String workspaceId,
            @BindMethods Collection<DatasetLastOptimizationCreated> datasets);

    @SqlQuery("""
                SELECT workspace_id, created_by AS user, COUNT(DISTINCT id) AS count
                FROM datasets
                WHERE created_at BETWEEN DATE_SUB(CURDATE(), INTERVAL 1 DAY) AND CURDATE()
                AND id NOT IN (
                    SELECT id
                    FROM datasets
                    WHERE workspace_id = :demo_workspace_id
                    AND name IN (<excluded_names>)
                )
                GROUP BY workspace_id, created_by
            """)
    List<BiInformationResponse.BiInformation> getDatasetsBIInformation(
            @Bind("demo_workspace_id") String demoWorkspaceId,
            @BindList("excluded_names") List<String> excludedNames);

    @SqlUpdate("CREATE TEMPORARY TABLE experiment_dataset_ids_<table_name> (id CHAR(36) PRIMARY KEY)")
    void createTempTable(@Define("table_name") String tableName);

    @SqlBatch("INSERT INTO experiment_dataset_ids_<table_name>(id) VALUES (:id)")
    int[] insertTempTable(@Define("table_name") String tableName, @Bind("id") List<UUID> id);

    @SqlUpdate("DROP TEMPORARY TABLE IF EXISTS experiment_dataset_ids_<table_name>")
    void dropTempTable(@Define("table_name") String tableName);

    @SqlQuery("SELECT id FROM datasets WHERE id IN (<ids>) and workspace_id = :workspace_id")
    Set<UUID> exists(@BindList("ids") Set<UUID> datasetIds, @Bind("workspace_id") String workspaceId);

    @SqlQuery("SELECT id FROM datasets WHERE id IN (SELECT id FROM experiment_dataset_ids_<table_name>) and workspace_id = :workspace_id")
    Set<UUID> existsByTempTable(@Bind("workspace_id") String workspaceId, @Define("table_name") String tableName);
}
