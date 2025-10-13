package com.comet.opik.domain;

import com.comet.opik.api.OptimizationAlgorithm;
import com.comet.opik.api.OptimizationStudioPromptMessage;
import com.comet.opik.api.OptimizationStudioRun;
import com.comet.opik.api.OptimizationStudioRunStatus;
import com.comet.opik.utils.JsonUtils;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@ImplementedBy(OptimizationStudioRunDAOImpl.class)
public interface OptimizationStudioRunDAO {

    Mono<Void> insert(String workspaceId, OptimizationStudioRun run, String createdBy);

    Mono<OptimizationStudioRun> findById(String workspaceId, UUID id);

}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class OptimizationStudioRunDAOImpl implements OptimizationStudioRunDAO {

    private static final String INSERT_STATEMENT = """
            INSERT INTO optimization_studio_runs (
                id, workspace_id, dataset_id, optimization_id, name, prompt, algorithm, metric, status, created_at, created_by, last_updated_at, last_updated_by
            ) VALUES (
                :id, :workspace_id, :dataset_id, :optimization_id, :name, :prompt, :algorithm, :metric, :status, now64(9), :created_by, now64(9), :last_updated_by
            )
            """;

    private static final String FIND_BY_ID = """
            SELECT * FROM optimization_studio_runs
            WHERE workspace_id = :workspace_id AND id = :id
            ORDER BY last_updated_at DESC
            LIMIT 1
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    @Override
    public Mono<Void> insert(@NonNull String workspaceId, @NonNull OptimizationStudioRun run, @NonNull String createdBy) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    Statement statement = connection.createStatement(INSERT_STATEMENT);

                    // Serialize prompt to JSON
                    String promptJson = run.prompt() != null ? JsonUtils.writeValueAsString(run.prompt()) : "[]";

                    statement
                            .bind("id", run.id().toString())
                            .bind("workspace_id", workspaceId)
                            .bind("dataset_id", run.datasetId().toString())
                            .bind("optimization_id", run.optimizationId() != null ? run.optimizationId().toString() : "")
                            .bind("name", run.name())
                            .bind("prompt", promptJson)
                            .bind("algorithm", run.algorithm().name().toLowerCase())
                            .bind("metric", run.metric())
                            .bind("status", OptimizationStudioRunStatus.RUNNING.name().toLowerCase())
                            .bind("created_by", createdBy)
                            .bind("last_updated_by", createdBy);

                    return statement.execute();
                })
                .then();
    }

    @Override
    public Mono<OptimizationStudioRun> findById(@NonNull String workspaceId, @NonNull UUID id) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    Statement statement = connection.createStatement(FIND_BY_ID);
                    statement
                            .bind("workspace_id", workspaceId)
                            .bind("id", id);

                    return statement.execute();
                })
                .flatMap(result -> result.map((row, rowMetadata) -> mapRow(row)))
                .next();
    }

    private OptimizationStudioRun mapRow(Row row) {
        // Parse prompt JSON
        String promptJson = row.get("prompt", String.class);
        List<OptimizationStudioPromptMessage> prompt = List.of();
        if (promptJson != null && !promptJson.isEmpty() && !promptJson.equals("[]")) {
            try {
                OptimizationStudioPromptMessage[] promptArray = JsonUtils.readValue(promptJson, OptimizationStudioPromptMessage[].class);
                prompt = Arrays.asList(promptArray);
            } catch (Exception e) {
                log.warn("Failed to parse prompt JSON: {}", promptJson, e);
            }
        }

        return OptimizationStudioRun.builder()
                .id(UUID.fromString(row.get("id", String.class)))
                .name(row.get("name", String.class))
                .datasetId(UUID.fromString(row.get("dataset_id", String.class)))
                .datasetName("") // Not stored in DB, would need to join with datasets table
                .optimizationId(parseUUID(row.get("optimization_id", String.class)))
                .prompt(prompt)
                .algorithm(OptimizationAlgorithm.valueOf(row.get("algorithm", String.class).toUpperCase()))
                .metric(row.get("metric", String.class))
                .status(OptimizationStudioRunStatus.valueOf(row.get("status", String.class).toUpperCase()))
                .createdAt(row.get("created_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .build();
    }

    private UUID parseUUID(String uuidString) {
        if (uuidString == null || uuidString.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(uuidString);
        } catch (IllegalArgumentException e) {
            log.warn("Failed to parse UUID: {}", uuidString);
            return null;
        }
    }
}
