package com.comet.opik.domain;

import com.comet.opik.api.Optimization;
import com.comet.opik.api.OptimizationStatus;
import com.google.common.base.Function;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.ExperimentDAO.getFeedbackScores;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.JsonUtils.getJsonNodeOrDefault;
import static com.comet.opik.utils.JsonUtils.getStringOrDefault;

@ImplementedBy(OptimizationDAOImpl.class)
public interface OptimizationDAO {

    Mono<Void> insert(Optimization experiment);

    Mono<Optimization> getById(UUID id);

}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class OptimizationDAOImpl implements OptimizationDAO {

    /**
     * The query validates if already exists with this id. Failing if so.
     * That way only insert is allowed, but not update.
     */
    private static final String INSERT = """
            INSERT INTO optimizations (
                id,
                dataset_id,
                name,
                workspace_id,
                objective_name,
                status,
                metadata,
                created_by,
                last_updated_by
            )
            SELECT
                if(
                    LENGTH(CAST(old.id AS Nullable(String))) > 0,
                    leftPad('', 40, '*'),
                    new.id
                ) as id,
                new.dataset_id,
                new.name,
                new.workspace_id,
                new.objective_name,
                new.status,
                new.metadata,
                new.created_by,
                new.last_updated_by
            FROM (
                SELECT
                :id AS id,
                :dataset_id AS dataset_id,
                :name AS name,
                :workspace_id AS workspace_id,
                :objective_name AS objective_name,
                :status AS status,
                :metadata AS metadata,
                :created_by AS created_by,
                :last_updated_by AS last_updated_by
            ) AS new
            LEFT JOIN (
                SELECT
                id
                FROM experiments
                WHERE id = :id
                ORDER BY last_updated_at DESC
                LIMIT 1 BY id
            ) AS old
            ON new.id = old.id
            ;
            """;

    private static final String FIND = """
            WITH trace_final AS (
                SELECT
                    id
                FROM traces
                WHERE workspace_id = :workspace_id
            ),
            feedback_scores_agg AS (
                SELECT
                    experiment_id,
                    if(
                        notEmpty(arrayFilter(x -> length(x) > 0, groupArray(name))),
                        mapFromArrays(
                            arrayDistinct(arrayFilter(x -> length(x) > 0, groupArray(name))),
                            arrayMap(
                                vName -> if(
                                    arrayReduce(
                                        'SUM',
                                        arrayMap(
                                            vNameAndValue -> vNameAndValue.2,
                                            arrayFilter(
                                                pair -> pair.1 = vName,
                                                groupArray(DISTINCT tuple(name, count_value, trace_id))
                                            )
                                        )
                                    ) = 0,
                                    0,
                                    arrayReduce(
                                        'SUM',
                                        arrayMap(
                                            vNameAndValue -> vNameAndValue.2,
                                            arrayFilter(
                                                pair -> pair.1 = vName,
                                                groupArray(DISTINCT tuple(name, total_value, trace_id))
                                            )
                                        )
                                    ) / arrayReduce(
                                        'SUM',
                                        arrayMap(
                                            vNameAndValue -> vNameAndValue.2,
                                            arrayFilter(
                                                pair -> pair.1 = vName,
                                                groupArray(DISTINCT tuple(name, count_value, trace_id))
                                            )
                                        )
                                    )
                                ),
                                arrayDistinct(arrayFilter(x -> length(x) > 0, groupArray(name)))
                            )
                        ),
                        map()
                    ) as feedback_scores
                FROM (
                    SELECT
                        ei.experiment_id,
                        tfs.name,
                        tfs.total_value,
                        tfs.count_value,
                        tfs.trace_id as trace_id
                    FROM experiment_items ei
                    JOIN (
                        SELECT
                            entity_id as trace_id,
                            name,
                            SUM(value) as total_value,
                            COUNT(value) as count_value
                        FROM (
                            SELECT
                                entity_id,
                                name,
                                value
                            FROM feedback_scores
                            WHERE entity_type = :entity_type
                            AND workspace_id = :workspace_id
                            AND entity_id IN (SELECT id FROM trace_final)
                            ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
                            LIMIT 1 BY entity_id, name
                        )
                        GROUP BY
                            entity_id,
                            name
                    ) AS tfs ON ei.trace_id = tfs.trace_id
                    WHERE ei.trace_id IN (SELECT id FROM trace_final)
                )
                GROUP BY experiment_id
            ), experiments_fs AS (
                SELECT
                e.id as id,
                e.optimization_id as optimization_id,
                fs.feedback_scores as feedback_scores
                FROM (
                    SELECT
                        *
                    FROM experiments
                    WHERE workspace_id = :workspace_id
                    ORDER BY id DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS e
                LEFT JOIN feedback_scores_agg AS fs ON e.id = fs.experiment_id
            )
            SELECT
                o.*,
                COUNT(DISTINCT efs.id) AS num_trials,
                maxMap(efs.feedback_scores) AS feedback_scores
            FROM (
                SELECT
                    *
                FROM optimizations
                WHERE workspace_id = :workspace_id
                <if(id)> AND id = :id <endif>
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS o
            LEFT JOIN experiments_fs AS efs ON o.id = efs.optimization_id
            GROUP BY o.*
            ORDER BY o.id DESC
            <if(limit)> LIMIT :limit <endif> <if(offset)> OFFSET :offset <endif>
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    @WithSpan
    public Mono<Void> insert(@NonNull Optimization optimization) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> insert(optimization, connection))
                .then();
    }

    @Override
    public Mono<Optimization> getById(@NonNull UUID id) {
        var template = new ST(FIND);
        template.add("id", id.toString());

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> get(
                        template.render(), connection,
                        statement -> statement.bind("id", id)))
                .flatMap(this::mapToDto)
                .singleOrEmpty();
    }

    private Publisher<? extends Result> insert(Optimization optimization, Connection connection) {
        var statement = connection.createStatement(INSERT)
                .bind("id", optimization.id())
                .bind("dataset_id", optimization.datasetId())
                .bind("name", optimization.name())
                .bind("objective_name", optimization.objectiveName())
                .bind("status", optimization.status().getValue())
                .bind("metadata", getStringOrDefault(optimization.metadata()));

        return makeFluxContextAware((userName, workspaceId) -> {
            log.info("Inserting optimization with id '{}', datasetId '{}', datasetName '{}', workspaceId '{}'",
                    optimization.id(), optimization.datasetId(), optimization.datasetName(), workspaceId);
            statement.bind("created_by", userName)
                    .bind("last_updated_by", userName)
                    .bind("workspace_id", workspaceId);
            return Flux.from(statement.execute());
        });
    }

    private Publisher<? extends Result> get(String query, Connection connection, Function<Statement, Statement> bind) {
        var statement = connection.createStatement(query)
                .bind("entity_type", EntityType.TRACE.getType());
        return makeFluxContextAware(bindWorkspaceIdToFlux(bind.apply(statement)));
    }

    private Publisher<Optimization> mapToDto(Result result) {
        return result.map((row, rowMetadata) -> {
            return Optimization.builder()
                    .id(row.get("id", UUID.class))
                    .name(row.get("name", String.class))
                    .datasetId(row.get("dataset_id", UUID.class))
                    .objectiveName(row.get("objective_name", String.class))
                    .status(OptimizationStatus.fromString(row.get("status", String.class)))
                    .metadata(getJsonNodeOrDefault(row.get("metadata", String.class)))
                    .createdAt(row.get("created_at", Instant.class))
                    .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                    .createdBy(row.get("created_by", String.class))
                    .lastUpdatedBy(row.get("last_updated_by", String.class))
                    .feedbackScores(getFeedbackScores(row))
                    .numTrials(row.get("num_trials", Long.class))
                    .build();
        });
    }
}
