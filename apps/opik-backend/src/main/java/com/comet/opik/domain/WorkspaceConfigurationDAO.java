package com.comet.opik.domain;

import com.comet.opik.api.WorkspaceConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContext;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(WorkspaceConfigurationDAOImpl.class)
public interface WorkspaceConfigurationDAO {

    Mono<Long> upsertConfiguration(String workspaceId, WorkspaceConfiguration configuration);

    Mono<WorkspaceConfiguration> getConfiguration(String workspaceId);

    Mono<Long> deleteConfiguration(String workspaceId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class WorkspaceConfigurationDAOImpl implements WorkspaceConfigurationDAO {

    private static final String UPSERT_CONFIGURATION_SQL = """
            INSERT INTO workspace_configurations (
                workspace_id,
                timeout_mark_thread_as_inactive,
                truncation_on_tables,
                color_map,
                created_at,
                last_updated_at,
                created_by,
                last_updated_by
            )
            SELECT
                new_config.workspace_id,
                new_config.timeout_mark_thread_as_inactive,
                new_config.truncation_on_tables,
                new_config.color_map,
                if(empty(wc.workspace_id), new_config.created_at, wc.created_at),
                new_config.last_updated_at,
                if(empty(wc.workspace_id), new_config.created_by, wc.created_by),
                new_config.last_updated_by
            FROM (
                SELECT
                    :workspace_id AS workspace_id,
                    :timeout_seconds AS timeout_mark_thread_as_inactive,
                    :truncation_on_tables AS truncation_on_tables,
                    :color_map AS color_map,
                    now64(9) AS created_at,
                    now64(6) AS last_updated_at,
                    :user_name AS created_by,
                    :user_name AS last_updated_by
            ) AS new_config
            LEFT JOIN workspace_configurations wc final ON wc.workspace_id = new_config.workspace_id
            """;

    private static final String GET_CONFIGURATION_SQL = """
            SELECT
                timeout_mark_thread_as_inactive,
                truncation_on_tables,
                color_map
            FROM workspace_configurations final
            WHERE workspace_id = :workspace_id
            """;

    private static final String DELETE_CONFIGURATION_SQL = """
            DELETE FROM workspace_configurations
            WHERE workspace_id = :workspace_id
            """;

    private static final TypeReference<Map<String, String>> COLOR_MAP_TYPE_REF = new TypeReference<>() {
    };

    private final @NonNull TransactionTemplateAsync asyncTemplate;

    @Override
    public Mono<Long> upsertConfiguration(@NonNull String workspaceId,
            @NonNull WorkspaceConfiguration configuration) {

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(UPSERT_CONFIGURATION_SQL)
                    .bind("workspace_id", workspaceId);

            if (configuration.timeoutToMarkThreadAsInactive() != null) {
                statement.bind("timeout_seconds", configuration.timeoutToMarkThreadAsInactive().toSeconds());
            } else {
                statement.bindNull("timeout_seconds", Long.class);
            }

            if (configuration.truncationOnTables() != null) {
                statement.bind("truncation_on_tables", configuration.truncationOnTables());
            } else {
                statement.bindNull("truncation_on_tables", Boolean.class);
            }

            if (configuration.colorMap() != null && !configuration.colorMap().isEmpty()) {
                statement.bind("color_map", JsonUtils.writeValueAsString(configuration.colorMap()));
            } else {
                statement.bind("color_map", "");
            }

            return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }

    @Override
    public Mono<WorkspaceConfiguration> getConfiguration(@NonNull String workspaceId) {
        log.info("Getting workspace configuration for workspace '{}'", workspaceId);

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(GET_CONFIGURATION_SQL)
                    .bind("workspace_id", workspaceId);

            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMap(result -> Mono.from(result.map((row, rowMetadata) -> {
                        Duration timeout = Optional.ofNullable(row.get("timeout_mark_thread_as_inactive", Long.class))
                                .filter(timeoutSeconds -> timeoutSeconds > 0)
                                .map(Duration::ofSeconds)
                                .orElse(null);

                        Boolean truncationOnTables = row.get("truncation_on_tables", Boolean.class);

                        String colorMapJson = row.get("color_map", String.class);
                        Map<String, String> colorMap = StringUtils.isNotEmpty(colorMapJson)
                                ? JsonUtils.readValue(colorMapJson, COLOR_MAP_TYPE_REF)
                                : null;

                        return WorkspaceConfiguration.builder()
                                .timeoutToMarkThreadAsInactive(timeout)
                                .truncationOnTables(truncationOnTables)
                                .colorMap(colorMap)
                                .build();
                    })));
        });
    }

    @Override
    public Mono<Long> deleteConfiguration(@NonNull String workspaceId) {
        log.info("Deleting workspace configuration for workspace '{}'", workspaceId);

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(DELETE_CONFIGURATION_SQL)
                    .bind("workspace_id", workspaceId);

            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }
}
