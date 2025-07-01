package com.comet.opik.domain;

import com.comet.opik.api.Project;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import io.r2dbc.spi.Row;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.Project.Configuration;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(ProjectConfigDAOImpl.class)
interface ProjectConfigDAO {

    Mono<Long> upsertConfigurations(Project project);

    Mono<Configuration> getConfigurations(UUID projectId);

    Mono<Map<UUID, Configuration>> getConfigurationsByIds(Set<UUID> projectIds);

    Mono<Long> deleteConfigurationsByProjectId(List<UUID> projectIds);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class ProjectConfigDAOImpl implements ProjectConfigDAO {

    private static final String UPSERT_TRACE_THREAD_TIMEOUT_SQL = """
            INSERT INTO project_configurations(workspace_id, project_id, timeout_mark_thread_as_inactive, created_at, last_updated_at, created_by, last_updated_by)
            VALUES (:workspace_id, :project_id, :timeout_mark_thread_as_inactive, parseDateTime64BestEffort(:created_at, 9), now64(6), :created_by, :user_name)
            """;

    private static final String GET_TRACE_THREAD_TIMEOUT_SQL = """
            SELECT *
            FROM project_configurations
            WHERE workspace_id = :workspace_id
            AND project_id IN :project_ids
            ORDER BY (workspace_id, project_id) DESC, last_updated_at DESC
            LIMIT 1 by project_id
            """;
    public static final String DELETE_CONFIGURATION_SQL = """
            DELETE FROM project_configurations WHERE project_id IN :project_ids AND workspace_id IN :workspace_id
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;

    @Override
    public Mono<Long> upsertConfigurations(@NonNull Project project) {
        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(UPSERT_TRACE_THREAD_TIMEOUT_SQL)
                    .bind("project_id", project.id())
                    .bind("created_at", project.createdAt().toString())
                    .bind("created_by", project.createdBy());

            if (project.configuration() != null && project.configuration().timeoutToMarkThreadAsInactive() != null) {
                Duration duration = project.configuration().timeoutToMarkThreadAsInactive();
                statement.bind("timeout_mark_thread_as_inactive", duration.getSeconds());
            } else {
                statement.bindNull("timeout_mark_thread_as_inactive", Long.class);
            }

            return makeMonoContextAware(AsyncContextUtils.bindUserNameAndWorkspaceContext(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }

    @Override
    public Mono<Configuration> getConfigurations(@NonNull UUID projectId) {
        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(GET_TRACE_THREAD_TIMEOUT_SQL)
                    .bind("project_ids", List.of(projectId));

            return makeMonoContextAware(AsyncContextUtils.bindWorkspaceIdToMono(statement))
                    .flatMap(result -> Mono.from(result.map((row, metadata) -> mapToDTO(row))));
        });
    }

    private static Configuration mapToDTO(Row row) {
        return Configuration.builder()
                .timeoutToMarkThreadAsInactive(
                        Optional.ofNullable(row.get("timeout_mark_thread_as_inactive", Long.class))
                                .filter(timeout -> timeout > 0)
                                .map(Duration::ofSeconds)
                                .orElse(null))
                .build();
    }

    @Override
    public Mono<Map<UUID, Configuration>> getConfigurationsByIds(@NonNull Set<UUID> projectIds) {
        return asyncTemplate.nonTransaction(connection -> {

            if (projectIds.isEmpty()) {
                return Mono.just(Map.of());
            }

            var statement = connection.createStatement(GET_TRACE_THREAD_TIMEOUT_SQL)
                    .bind("project_ids", projectIds);

            return makeFluxContextAware(AsyncContextUtils.bindWorkspaceIdToFlux(statement))
                    .flatMap(result -> result.map((row, metadata) -> {
                        UUID projectId = row.get("project_id", UUID.class);
                        Configuration configuration = mapToDTO(row);
                        return Map.entry(projectId, configuration);
                    }))
                    .collectMap(Map.Entry::getKey, Map.Entry::getValue);
        });
    }

    @Override
    public Mono<Long> deleteConfigurationsByProjectId(@NonNull List<UUID> projectIds) {
        if (projectIds.isEmpty()) {
            return Mono.just(0L);
        }

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection
                    .createStatement(DELETE_CONFIGURATION_SQL)
                    .bind("project_ids", projectIds);

            return makeMonoContextAware(AsyncContextUtils.bindWorkspaceIdToMono(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }

}