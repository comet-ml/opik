package com.comet.opik.domain;

import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetLastExperimentCreated;
import com.comet.opik.api.DatasetLastOptimizationCreated;
import com.comet.opik.api.DatasetStatus;
import com.comet.opik.api.DatasetUpdate;
import com.comet.opik.api.Visibility;
import com.comet.opik.infrastructure.db.DatasetTypeMapper;
import com.comet.opik.infrastructure.db.SetFlatArgumentFactory;
import com.comet.opik.infrastructure.db.UUIDArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterArgumentFactory;
import org.jdbi.v3.sqlobject.config.RegisterColumnMapper;
import org.jdbi.v3.sqlobject.config.RegisterConstructorMapper;
import org.jdbi.v3.sqlobject.customizer.AllowUnusedBindings;
import org.jdbi.v3.sqlobject.customizer.Bind;
import org.jdbi.v3.sqlobject.customizer.BindList;
import org.jdbi.v3.sqlobject.customizer.BindMap;
import org.jdbi.v3.sqlobject.customizer.BindMethods;
import org.jdbi.v3.sqlobject.customizer.Define;
import org.jdbi.v3.sqlobject.statement.SqlBatch;
import org.jdbi.v3.sqlobject.statement.SqlQuery;
import org.jdbi.v3.sqlobject.statement.SqlUpdate;
import org.jdbi.v3.stringtemplate4.UseStringTemplateEngine;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@RegisterArgumentFactory(UUIDArgumentFactory.class)
@RegisterConstructorMapper(Dataset.class)
@RegisterConstructorMapper(BiInformationResponse.BiInformation.class)
@RegisterConstructorMapper(EligibleDatasetWorkspace.class)
@RegisterArgumentFactory(SetFlatArgumentFactory.class)
@RegisterColumnMapper(SetFlatArgumentFactory.class)
@RegisterArgumentFactory(DatasetTypeMapper.class)
@RegisterColumnMapper(DatasetTypeMapper.class)
public interface DatasetDAO {

    /**
     * Checks for V1 (workspace-scoped) datasets excluding known demo names.
     * MySQL utf8mb4_unicode_ci collation makes the NOT IN comparison case-insensitive,
     * so demo name variants differing only in casing are automatically excluded.
     */
    @SqlQuery("""
            SELECT EXISTS(
                SELECT 1 FROM datasets
                WHERE workspace_id = :workspaceId AND project_id IS NULL
                AND name NOT IN (<demoDatasetNames>)
            )""")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    boolean hasVersion1Datasets(@Bind("workspaceId") String workspaceId,
            @BindList("demoDatasetNames") List<String> demoDatasetNames);

    @SqlUpdate("INSERT INTO datasets(id, name, description, visibility, type, workspace_id, project_id, created_by, last_updated_by, tags) "
            +
            "VALUES (:dataset.id, :dataset.name, :dataset.description, COALESCE(:dataset.visibility, 'private'), COALESCE(:dataset.type, 'dataset'), :workspace_id, :dataset.projectId, :dataset.createdBy, :dataset.lastUpdatedBy, :dataset.tags)")
    void save(@BindMethods("dataset") Dataset dataset, @Bind("workspace_id") String workspaceId);

    @SqlUpdate("""
            UPDATE datasets SET
                name = :dataset.name,
                description = :dataset.description,
                visibility = COALESCE(:dataset.visibility, visibility),
                tags = COALESCE(:dataset.tags, tags),
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

    @SqlQuery("""
            SELECT COUNT(id) FROM datasets
            WHERE workspace_id = :workspace_id
            <if(name)> AND name like concat('%', :name, '%') <endif>
            <if(project_id)> AND project_id = :project_id <endif>
            <if(filters)> AND <filters> <endif>
            <if(visibility)> AND visibility = :visibility <endif>
            <if(with_experiments_only)> AND last_created_experiment_at IS NOT NULL <endif>
            <if(with_optimizations_only)> AND last_created_optimization_at IS NOT NULL <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCount(@Bind("workspace_id") String workspaceId, @Define("name") @Bind("name") String name,
            @Define("project_id") @Bind("project_id") UUID projectId,
            @Define("with_experiments_only") boolean withExperimentsOnly,
            @Define("with_optimizations_only") boolean withOptimizationOnly,
            @Define("visibility") @Bind("visibility") Visibility visibility,
            @Define("filters") String filters,
            @BindMap Map<String, Object> filterMapping);

    @SqlQuery("""
            SELECT COUNT(id) FROM datasets
            WHERE workspace_id = :workspace_id
            AND id IN (<ids>)
            <if(name)> AND name like concat('%', :name, '%') <endif>
            <if(filters)> AND <filters> <endif>
            <if(visibility)> AND visibility = :visibility <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCountByIds(@Bind("workspace_id") String workspaceId, @BindList("ids") Set<UUID> ids,
            @Define("name") @Bind("name") String name,
            @Define("visibility") @Bind("visibility") Visibility visibility,
            @Define("filters") String filters,
            @BindMap Map<String, Object> filterMapping);

    @SqlQuery("""
            SELECT * FROM datasets
            WHERE workspace_id = :workspace_id
            AND id IN (<ids>)
            <if(name)> AND name like concat('%', :name, '%') <endif>
            <if(filters)> AND <filters> <endif>
            <if(visibility)> AND visibility = :visibility <endif>
            ORDER BY <if(sort_fields)> <sort_fields>, <endif> id DESC
            LIMIT :limit OFFSET :offset
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<Dataset> findByIds(@Bind("workspace_id") String workspaceId, @BindList("ids") Set<UUID> ids,
            @Define("name") @Bind("name") String name, @Bind("limit") int limit, @Bind("offset") int offset,
            @Define("sort_fields") @Bind("sort_fields") String sortingFields,
            @Define("visibility") @Bind("visibility") Visibility visibility,
            @Define("filters") String filters,
            @BindMap Map<String, Object> filterMapping);

    @SqlQuery("""
            SELECT COUNT(id) FROM datasets
            WHERE workspace_id = :workspace_id
            AND id IN (SELECT id FROM experiment_dataset_ids_<table_name>)
            <if(name)> AND name like concat('%', :name, '%') <endif>
            <if(filters)> AND <filters> <endif>
            <if(visibility)> AND visibility = :visibility <endif>
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    long findCountByTempTable(@Bind("workspace_id") String workspaceId, @Define("table_name") String tableName,
            @Define("name") @Bind("name") String name,
            @Define("visibility") @Bind("visibility") Visibility visibility,
            @Define("filters") String filters,
            @BindMap Map<String, Object> filterMapping);

    @SqlQuery("""
            SELECT * FROM datasets
            WHERE workspace_id = :workspace_id
            AND id IN (SELECT id FROM experiment_dataset_ids_<table_name>)
            <if(name)> AND name like concat('%', :name, '%') <endif>
            <if(filters)> AND <filters> <endif>
            <if(visibility)> AND visibility = :visibility <endif>
            ORDER BY <if(sort_fields)> <sort_fields>, <endif> id DESC
            LIMIT :limit OFFSET :offset
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<Dataset> findByTempTable(@Bind("workspace_id") String workspaceId, @Define("table_name") String tableName,
            @Define("name") @Bind("name") String name, @Bind("limit") int limit, @Bind("offset") int offset,
            @Define("sort_fields") @Bind("sort_fields") String sortingFields,
            @Define("visibility") @Bind("visibility") Visibility visibility,
            @Define("filters") String filters,
            @BindMap Map<String, Object> filterMapping);

    @SqlQuery("""
            SELECT * FROM datasets
            WHERE workspace_id = :workspace_id
            <if(name)> AND name like concat('%', :name, '%') <endif>
            <if(project_id)> AND project_id = :project_id <endif>
            <if(filters)> AND <filters> <endif>
            <if(visibility)> AND visibility = :visibility <endif>
            <if(with_experiments_only)> AND last_created_experiment_at IS NOT NULL <endif>
            <if(with_optimizations_only)> AND last_created_optimization_at IS NOT NULL <endif>
            ORDER BY <if(sort_fields)> <sort_fields>, <endif> id DESC
            LIMIT :limit OFFSET :offset
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<Dataset> find(@Bind("limit") int limit,
            @Bind("offset") int offset,
            @Bind("workspace_id") String workspaceId,
            @Define("name") @Bind("name") String name,
            @Define("project_id") @Bind("project_id") UUID projectId,
            @Define("with_experiments_only") boolean withExperimentsOnly,
            @Define("with_optimizations_only") boolean withOptimizationOnly,
            @Define("sort_fields") @Bind("sort_fields") String sortingFields,
            @Define("visibility") @Bind("visibility") Visibility visibility,
            @Define("filters") String filters,
            @BindMap Map<String, Object> filterMapping);

    @SqlQuery("SELECT * FROM datasets WHERE workspace_id = :workspace_id AND name = :name" +
            " <if(project_id)> AND project_id = :project_id <endif>")
    @UseStringTemplateEngine
    @AllowUnusedBindings
    Optional<Dataset> findByName(@Bind("workspace_id") String workspaceId, @Bind("name") String name,
            @Define("project_id") @Bind("project_id") UUID projectId);

    @SqlQuery("SELECT id FROM datasets WHERE workspace_id = :workspace_id AND name LIKE CONCAT('%', :name, '%') ESCAPE '\\\\'")
    List<UUID> findIdsByPartialName(@Bind("workspace_id") String workspaceId, @Bind("name") String name);

    static String escapeLikeMetacharacters(String input) {
        return input.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
    }

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
                    WHERE name IN (<excluded_names>)
                )
                GROUP BY workspace_id, created_by
            """)
    List<BiInformationResponse.BiInformation> getDatasetsBIInformation(
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

    @SqlUpdate("UPDATE datasets SET status = :status WHERE id = :id AND workspace_id = :workspace_id")
    int updateStatus(@Bind("workspace_id") String workspaceId,
            @Bind("id") UUID id,
            @Bind("status") DatasetStatus status);

    /**
     * Returns workspaces with at least one V1 dataset (project_id IS NULL), ordered by smallest
     * count first. {@code FORCE INDEX} and the {@code workspace_id NOT IN (...)} predicate are
     * only emitted when {@code excludedWorkspaceIds} is non-empty: without the hint, the {@code
     * NOT IN} predicate makes the planner fall back to a full table scan; forcing the
     * {@code (workspace_id, name)} uniqueness index turns it into a range scan — validated
     * against prod (220k → 170k rows, 1.4s → 0.35s).
     */
    @SqlQuery("""
            SELECT workspace_id, COUNT(*) AS datasets_count
            FROM datasets <if(excludedWorkspaceIds)>FORCE INDEX (datasets_workspace_id_name_uk)<endif>
            WHERE project_id IS NULL
            AND name NOT IN (<demoDatasetNames>)
            <if(excludedWorkspaceIds)> AND workspace_id NOT IN (<excludedWorkspaceIds>) <endif>
            GROUP BY workspace_id
            ORDER BY datasets_count ASC, workspace_id ASC
            LIMIT :limit
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    List<EligibleDatasetWorkspace> findEligibleDatasetMigrationWorkspaces(
            @BindList("demoDatasetNames") List<String> demoDatasetNames,
            @Define("excludedWorkspaceIds") @BindList(onEmpty = BindList.EmptyHandling.NULL_VALUE, value = "excludedWorkspaceIds") Collection<String> excludedWorkspaceIds,
            @Bind("limit") int limit);

    @SqlBatch("""
            UPDATE datasets SET project_id = :projectId, last_updated_by = :userName
            WHERE id = :datasetId AND workspace_id = :workspaceId AND project_id IS NULL
            """)
    int[] batchSetProjectId(
            @BindMethods List<DatasetProjectMapping> mappings,
            @Bind("workspaceId") String workspaceId,
            @Bind("userName") String userName);

    // V1 dataset IDs in the workspace (excludes demo names). Service uses this to detect the
    // no-inference bucket — orphans present here but absent from the CH inference result.
    @SqlQuery("""
            SELECT id FROM datasets
            WHERE workspace_id = :workspace_id
            AND project_id IS NULL
            AND name NOT IN (<demoDatasetNames>)
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    Set<UUID> findOrphanDatasetIdsInWorkspace(
            @Bind("workspace_id") String workspaceId,
            @BindList("demoDatasetNames") List<String> demoDatasetNames);

    /**
     * Bulk lookup of {@code (dataset_id, project_id)} pairs scoped to a workspace. Returns one row
     * per matching dataset_id; datasets whose {@code project_id} is still {@code NULL} are
     * <b>omitted</b>, so callers detect them by diffing against their candidate set.
     *
     * <p>Used by {@code OptimizationProjectMigrationService} for Path B inference: orphan
     * optimizations that Path A (experiments) does not classify look up their dataset's
     * {@code project_id} here in a single round-trip per workspace cycle.
     */
    @SqlQuery("""
            SELECT id, project_id FROM datasets
            WHERE workspace_id = :workspace_id
            AND id IN (<ids>)
            AND project_id IS NOT NULL
            """)
    @UseStringTemplateEngine
    @AllowUnusedBindings
    @RegisterConstructorMapper(DatasetProjectIdRow.class)
    List<DatasetProjectIdRow> findProjectIdsByDatasetIds(
            @Bind("workspace_id") String workspaceId,
            @BindList("ids") Set<UUID> ids);
}
