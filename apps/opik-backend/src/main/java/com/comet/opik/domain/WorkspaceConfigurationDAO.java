package com.comet.opik.domain;

import com.comet.opik.api.WorkspaceConfiguration;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.ClickhouseUtils;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Mono;

import java.time.Duration;
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
                created_at,
                last_updated_at,
                created_by,
                last_updated_by
            ) <settings_clause>
            SELECT
                new_config.workspace_id,
                new_config.timeout_mark_thread_as_inactive,
                if(empty(wc.workspace_id), new_config.created_at, wc.created_at),
                new_config.last_updated_at,
                if(empty(wc.workspace_id), new_config.created_by, wc.created_by),
                new_config.last_updated_by
            FROM (
                SELECT
                    :workspace_id AS workspace_id,
                    :timeout_seconds AS timeout_mark_thread_as_inactive,
                    now64(9) AS created_at,
                    now64(6) AS last_updated_at,
                    :user_name AS created_by,
                    :user_name AS last_updated_by
            ) AS new_config
            LEFT JOIN workspace_configurations wc final ON wc.workspace_id = new_config.workspace_id
            """;

    private static final String GET_CONFIGURATION_SQL = """
            SELECT timeout_mark_thread_as_inactive
            FROM workspace_configurations final
            WHERE workspace_id = :workspace_id
            """;

    private static final String DELETE_CONFIGURATION_SQL = """
            DELETE FROM workspace_configurations
            WHERE workspace_id = :workspace_id
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull OpikConfiguration opikConfiguration;

    @Override
    public Mono<Long> upsertConfiguration(@NonNull String workspaceId,
            @NonNull WorkspaceConfiguration configuration) {

        ST template = new ST(UPSERT_CONFIGURATION_SQL);

        ClickhouseUtils.checkAsyncConfig(template, opikConfiguration.getAsyncInsert());

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId);

            if (configuration.timeoutToMarkThreadAsInactive() != null) {
                statement.bind("timeout_seconds", configuration.timeoutToMarkThreadAsInactive().toSeconds());
            } else {
                statement.bindNull("timeout_seconds", Long.class);
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

                        return WorkspaceConfiguration.builder()
                                .timeoutToMarkThreadAsInactive(timeout)
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
