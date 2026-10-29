package com.comet.opik.domain.workspaces;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.WorkspaceSettings;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.util.Optional;
import java.util.UUID;

@ImplementedBy(WorkspaceMetadataDAOImpl.class)
interface WorkspaceMetadataDAO {
    Mono<ScopeMetadata> getProjectMetadata(String workspaceId, UUID projectId);

    Mono<ExperimentScopeMetadata> getExperimentMetadata(String workspaceId, UUID datasetId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class WorkspaceMetadataDAOImpl implements WorkspaceMetadataDAO {

    /**
     * Under {@code spanWeeksReadBounded}, both {@code spans} reads are bound to the weeks {@code span_weeks} holds for
     * the project, so they skip the partitions without a span of the project.
     */
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
                        <if(project_span_weeks_bound)>AND toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1))) IN (
                            SELECT DISTINCT id_week FROM span_weeks
                            WHERE workspace_id = :workspace_id AND project_id = :project_id)<endif>
                        ORDER BY (workspace_id, project_id, trace_id, id) DESC
                        LIMIT 1000
                    )
                ),
            	total_spans AS (
            			SELECT
            			    uniq(id) as total_count
            			FROM spans
            			WHERE workspace_id = :workspace_id
            			AND project_id = :project_id
            			<if(project_span_weeks_bound)>AND toYYYYMMDD(toDate32(id_at) - toIntervalDay(toDayOfWeek(id_at, 1))) IN (
            			    SELECT DISTINCT id_week FROM span_weeks
            			    WHERE workspace_id = :workspace_id AND project_id = :project_id)<endif>
            	)

            SELECT
                round((query_result.query_size * total_spans.total_count) / 1e9, 2) AS size_gb_,
                if(isNaN(size_gb_), 0, size_gb_) AS size_gb
            FROM query_result, total_spans;
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

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull WorkspaceSettings workspaceSettings;
    private final @NonNull OpikConfiguration configuration;

    @Override
    public Mono<ScopeMetadata> getProjectMetadata(@NonNull String workspaceId, @NonNull UUID projectId) {
        var template = TemplateUtils.newST(CALCULATE_METADATA);
        if (configuration.getDatabaseAnalyticsDataModel().spanWeeksReadBounded()) {
            template.add("project_span_weeks_bound", true);
        }
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(template.render())
                            .bind("workspace_id", workspaceId)
                            .bind("project_id", projectId);
                    return statement.execute();
                })
                .flatMap(result -> result.map((row, rowMetadata) -> ScopeMetadata.builder()
                        .sizeGb(Optional.ofNullable(row.get("size_gb", Double.class)).orElse(0.0))
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
