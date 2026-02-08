package com.comet.opik.domain.workspaces;

import com.comet.opik.infrastructure.WorkspaceSettings;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.NonNull;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

@ImplementedBy(WorkspaceMetadataDAOImpl.class)
interface WorkspaceMetadataDAO {
    Mono<ScopeMetadata> getProjectMetadata(String workspaceId, UUID projectId);

    Mono<ExperimentScopeMetadata> getExperimentMetadata(String workspaceId, UUID datasetId);
}

@Singleton
class WorkspaceMetadataDAOImpl implements WorkspaceMetadataDAO {

    private static final String CALCULATE_METADATA = """
            WITH
                query_result AS (
                    SELECT AVG(query_size) AS query_size
                    FROM (
                        SELECT input_length +
                                output_length +
                                metadata_length +
                                OCTET_LENGTH(error_info) +
                                OCTET_LENGTH(toJSONString(tags)) +
                                OCTET_LENGTH(toJSONString(usage)) as query_size
                        FROM spans
                        WHERE workspace_id = :workspace_id
                        AND project_id = :project_id
                        ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC
                        LIMIT 1000
                    )
                ),
            	total_spans AS (
            			SELECT
            			    count(distinct id) as total_count
            			FROM spans
            			WHERE workspace_id = :workspace_id
            			AND project_id = :project_id
            	),
                table_size AS (
                    SELECT
                        SUM(data_uncompressed_bytes) AS total_compressed_size
                    FROM system.parts
                    WHERE database = :database_name
                    AND table = 'spans'
                    AND active
                )

            SELECT
                round((query_result.query_size * total_spans.total_count) / 1e9, 2) AS size_gb_,
                if(isNaN(size_gb_), 0, size_gb_) AS size_gb,
                round(table_size.total_compressed_size / 1e9, 2) AS total_table_size_gb_,
                if(isNaN(total_table_size_gb_), 0, total_table_size_gb_) AS total_table_size_gb,
                round((size_gb / total_table_size_gb) * 100, 2) AS percentage_of_table
            FROM query_result, table_size, total_spans;
            """;

    private static final String CALCULATE_EXPERIMENT_METADATA = """
            SELECT count(DISTINCT ei.id) as experiment_items_count
            FROM experiment_items ei
            <if(dataset_id)>
            INNER JOIN experiments e ON ei.experiment_id = e.id AND ei.workspace_id = e.workspace_id
            <endif>
            WHERE ei.workspace_id = :workspace_id
            <if(dataset_id)>AND e.dataset_id = :dataset_id<endif>
            """;

    private final ConnectionFactory connectionFactory;
    private final String databaseName;
    private final WorkspaceSettings workspaceSettings;

    @Inject
    public WorkspaceMetadataDAOImpl(
            @NonNull ConnectionFactory connectionFactory,
            @NonNull @Named("Database Analytics Database Name") String databaseName,
            @NonNull WorkspaceSettings workspaceSettings) {
        this.connectionFactory = connectionFactory;
        this.databaseName = databaseName;
        this.workspaceSettings = workspaceSettings;
    }

    @Override
    public Mono<ScopeMetadata> getProjectMetadata(@NonNull String workspaceId, @NonNull UUID projectId) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(CALCULATE_METADATA)
                            .bind("workspace_id", workspaceId)
                            .bind("database_name", databaseName)
                            .bind("project_id", projectId);
                    return statement.execute();
                })
                .flatMap(result -> result.map((row, rowMetadata) -> ScopeMetadata.builder()
                        .sizeGb(Optional.ofNullable(row.get("size_gb", Double.class)).orElse(0.0))
                        .totalTableSizeGb(Optional.ofNullable(row.get("total_table_size_gb", Double.class)).orElse(0.0))
                        .percentageOfTable(
                                Optional.ofNullable(row.get("percentage_of_table", Double.class)).orElse(0.0))
                        .limitSizeGb(workspaceSettings.maxProjectSizeToAllowSorting())
                        .build()))
                .single(ScopeMetadata.builder()
                        .limitSizeGb(workspaceSettings.maxProjectSizeToAllowSorting())
                        .build());
    }

    @Override
    public Mono<ExperimentScopeMetadata> getExperimentMetadata(@NonNull String workspaceId, UUID datasetId) {
        var template = TemplateUtils.newST(CALCULATE_EXPERIMENT_METADATA);
        if (datasetId != null) {
            template.add("dataset_id", datasetId);
        }
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("workspace_id", workspaceId);
                    if (datasetId != null) {
                        statement.bind("dataset_id", datasetId);
                    }
                    return statement.execute();
                })
                .flatMap(result -> result.map((row, rowMetadata) -> ExperimentScopeMetadata.builder()
                        .experimentItemsCount(Optional.ofNullable(row.get("experiment_items_count", Long.class))
                                .orElse(0L))
                        .limitCount(workspaceSettings.maxExperimentItemsToAllowSorting())
                        .build()))
                .single(ExperimentScopeMetadata.builder()
                        .experimentItemsCount(0L)
                        .limitCount(workspaceSettings.maxExperimentItemsToAllowSorting())
                        .build());
    }
}
