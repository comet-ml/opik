package com.comet.opik.domain.workspaces;

import com.comet.opik.infrastructure.WorkspaceSettings;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.NonNull;
import reactor.core.publisher.Mono;

import java.util.UUID;

@ImplementedBy(WorkspaceMetadataDAOImpl.class)
interface WorkspaceMetadataDAO {
    Mono<WorkspaceMetadata> getWorkspaceMetadata(@NonNull String workspaceId);

    Mono<ProjectMetadata> getProjectMetadata(@NonNull String workspaceId, @NonNull UUID projectId);
}

@Singleton
class WorkspaceMetadataDAOImpl implements WorkspaceMetadataDAO {

    private static final String GET_WORKSPACE_METADATA = """
            WITH
                query_result AS (
                    SELECT AVG(query_size) AS query_size
                    FROM (
                        SELECT input_length +
                                output_length +
                                metadata_length +
                                OCTET_LENGTH(error_info) +
                                (OCTET_LENGTH(tags) * 10) +
                                OCTET_LENGTH(toJSONString(usage)) as query_size
                        FROM spans
                        WHERE workspace_id = :workspace_id
                        ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC
                        LIMIT 1000
                    )
                ),
            	total_spans AS (
            			SELECT count(distinct id) as total_count FROM spans
            			WHERE workspace_id = :workspace_id
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
                round((query_result.query_size * total_spans.total_count) / 1e9, 2) AS workspace_size_gb_,
                if(isNaN(workspace_size_gb_), 0, workspace_size_gb_) AS workspace_size_gb,
                round(table_size.total_compressed_size / 1e9, 2) AS total_table_size_gb_,
                if(isNaN(total_table_size_gb_), 0, total_table_size_gb_) AS total_table_size_gb,
                round((workspace_size_gb / total_table_size_gb) * 100, 2) AS percentage_of_table
            FROM query_result, table_size, total_spans;
            """;

    private static final String GET_PROJECT_METADATA = """
            WITH
                query_result AS (
                    SELECT AVG(query_size) AS query_size
                    FROM (
                        SELECT input_length +
                                output_length +
                                metadata_length +
                                OCTET_LENGTH(error_info) +
                                (OCTET_LENGTH(tags) * 10) +
                                OCTET_LENGTH(toJSONString(usage)) as query_size
                        FROM spans
                        WHERE workspace_id = :workspace_id
                        AND project_id = :project_id
                        ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC
                        LIMIT 1000
                    )
                ),
            	total_spans AS (
            			SELECT count(distinct id) as total_count FROM spans
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
                round((query_result.query_size * total_spans.total_count) / 1e9, 2) AS project_size_gb_,
                if(isNaN(project_size_gb_), 0, project_size_gb_) AS project_size_gb,
                round(table_size.total_compressed_size / 1e9, 2) AS total_table_size_gb_,
                if(isNaN(total_table_size_gb_), 0, total_table_size_gb_) AS total_table_size_gb,
                round((project_size_gb / total_table_size_gb) * 100, 2) AS percentage_of_table
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

    public Mono<WorkspaceMetadata> getWorkspaceMetadata(@NonNull String workspaceId) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(GET_WORKSPACE_METADATA)
                        .bind("workspace_id", workspaceId)
                        .bind("database_name", databaseName)
                        .execute())
                .flatMap(result -> result.map((row, rowMetadata) -> new WorkspaceMetadata(
                        row.get("workspace_size_gb", Double.class),
                        row.get("total_table_size_gb", Double.class),
                        row.get("percentage_of_table", Double.class),
                        workspaceSettings)))
                .single(new WorkspaceMetadata(0, 0, 0, workspaceSettings));
    }

    public Mono<ProjectMetadata> getProjectMetadata(@NonNull String workspaceId, @NonNull UUID projectId) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(GET_PROJECT_METADATA)
                        .bind("workspace_id", workspaceId)
                        .bind("project_id", projectId)
                        .bind("database_name", databaseName)
                        .execute())
                .flatMap(result -> result.map((row, rowMetadata) -> new ProjectMetadata(
                        row.get("project_size_gb", Double.class),
                        row.get("total_table_size_gb", Double.class),
                        row.get("percentage_of_table", Double.class),
                        workspaceSettings)))
                .single(new ProjectMetadata(0, 0, 0, workspaceSettings));
    }

}
