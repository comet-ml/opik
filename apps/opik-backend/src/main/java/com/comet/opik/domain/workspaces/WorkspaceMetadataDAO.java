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
    Mono<ScopeMetadata> getWorkspaceMetadata(String workspaceId);

    Mono<ScopeMetadata> getProjectMetadata(String workspaceId, UUID projectId);
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
                        <if(project_id)>AND project_id = :project_id<endif>
                        ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC
                        LIMIT 1000
                    )
                ),
            	total_spans AS (
            			SELECT
            			    count(distinct id) as total_count
            			FROM spans
            			WHERE workspace_id = :workspace_id
            			<if(project_id)>AND project_id = :project_id<endif>
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

    public Mono<ScopeMetadata> getWorkspaceMetadata(@NonNull String workspaceId) {
        return getMetadata(workspaceId, null)
                .map(metadata -> metadata.toBuilder()
                        .limitSizeGb(workspaceSettings.maxSizeToAllowSorting())
                        .build());
    }

    public Mono<ScopeMetadata> getProjectMetadata(@NonNull String workspaceId, @NonNull UUID projectId) {
        return getMetadata(workspaceId, projectId)
                .map(metadata -> metadata.toBuilder()
                        .limitSizeGb(workspaceSettings.maxProjectSizeToAllowSorting())
                        .build());
    }

    private Mono<ScopeMetadata> getMetadata(String workspaceId, UUID projectId) {
        var template = TemplateUtils.newST(CALCULATE_METADATA);
        if (projectId != null) {
            template.add("project_id", projectId);
        }
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("workspace_id", workspaceId)
                            .bind("database_name", databaseName);
                    if (projectId != null) {
                        statement.bind("project_id", projectId);
                    }
                    return statement.execute();
                })
                .flatMap(result -> result.map((row, rowMetadata) -> ScopeMetadata.builder()
                        .sizeGb(Optional.ofNullable(row.get("size_gb", Double.class)).orElse(0.0))
                        .totalTableSizeGb(Optional.ofNullable(row.get("total_table_size_gb", Double.class)).orElse(0.0))
                        .percentageOfTable(
                                Optional.ofNullable(row.get("percentage_of_table", Double.class)).orElse(0.0))
                        .build()))
                .single(ScopeMetadata.builder().build());
    }
}
