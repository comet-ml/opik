package com.comet.opik.domain;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class ExperimentDAO {

    /**
     * The query validates if already exists with this id. Failing if so.
     * That way only insert is allowed, but not update.
     */
    private static final String INSERT = """
            INSERT INTO experiments (
                id,
                dataset_id,
                name,
                workspace_id,
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
                new.metadata,
                new.created_by,
                new.last_updated_by
            FROM (
                SELECT
                :id AS id,
                :dataset_id AS dataset_id,
                :name AS name,
                :workspace_id AS workspace_id,
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

    private static final String SELECT_BY_ID = """
            SELECT
                e.workspace_id as workspace_id,
                e.dataset_id as dataset_id,
                e.id as id,
                e.name as name,
                e.metadata as metadata,
                e.created_at as created_at,
                e.last_updated_at as last_updated_at,
                e.created_by as created_by,
                e.last_updated_by as last_updated_by,
                if(
                     notEmpty(arrayFilter(x -> length(x) > 0, groupArray(tfs.name))),
                     arrayMap(
                        vName -> (
                            vName,
                            if(
                                arrayReduce(
                                    'SUM',
                                    arrayMap(
                                        vNameAndValue ->
                                            vNameAndValue.2,
                                            arrayFilter(
                                                (pair -> pair.1 = vName),
                                                groupArray(DISTINCT tuple(tfs.name, tfs.count_value, tfs.id))
                                            )
                                    )
                                ) = 0,
                                0,
                                arrayReduce(
                                    'SUM',
                                    arrayMap(
                                        vNameAndValue ->
                                            vNameAndValue.2,
                                            arrayFilter(
                                                (pair -> pair.1 = vName),
                                                groupArray(DISTINCT tuple(tfs.name, tfs.total_value, tfs.id))
                                            )
                                    )
                                ) / arrayReduce(
                                    'SUM',
                                    arrayMap(
                                        vNameAndValue ->
                                            vNameAndValue.2,
                                            arrayFilter(
                                                (pair -> pair.1 = vName),
                                                groupArray(DISTINCT tuple(tfs.name, tfs.count_value, tfs.id))
                                            )
                                    )
                                )
                            )
                        ),
                        arrayDistinct(arrayMap(vName -> vName.1, arrayFilter(curName -> length(curName.1) > 0, groupArray(tuple(tfs.name)))))
                    ),
                    []
                 ) as feedback_scores,
                count (DISTINCT ei.trace_id) as trace_count
            FROM (
                SELECT
                    *
                FROM experiments
                WHERE id = :id
                AND workspace_id = :workspace_id
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS e
            LEFT JOIN (
                SELECT
                    experiment_id,
                    trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS ei ON e.id = ei.experiment_id
            LEFT JOIN (
                SELECT
                    t.id,
                    fs.name,
                    SUM(value) as total_value,
                    COUNT(value) as count_value
                FROM (
                    SELECT
                        id
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    ORDER BY id DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS t
                INNER JOIN (
                    SELECT
                        entity_id,
                        name,
                        value
                    FROM feedback_scores
                    WHERE entity_type = :entity_type
                    AND workspace_id = :workspace_id
                    ORDER BY entity_id DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                ) AS fs ON t.id = fs.entity_id
                GROUP BY
                    t.id,
                    fs.name
            ) AS tfs ON ei.trace_id = tfs.id
            GROUP BY
                e.workspace_id,
                e.dataset_id,
                e.id,
                e.name,
                e.metadata as metadata,
                e.created_at,
                e.last_updated_at,
                e.created_by,
                e.last_updated_by
            ORDER BY e.id DESC
            ;
            """;

    private static final String FIND = """
            SELECT
                e.workspace_id as workspace_id,
                e.dataset_id as dataset_id,
                e.id as id,
                e.name as name,
                e.metadata as metadata,
                e.created_at as created_at,
                e.last_updated_at as last_updated_at,
                e.created_by as created_by,
                e.last_updated_by as last_updated_by,
                if(
                     notEmpty(arrayFilter(x -> length(x) > 0, groupArray(tfs.name))),
                     arrayMap(
                        vName -> (
                            vName,
                            if(
                                arrayReduce(
                                    'SUM',
                                    arrayMap(
                                        vNameAndValue ->
                                            vNameAndValue.2,
                                            arrayFilter(
                                                (pair -> pair.1 = vName),
                                                groupArray(DISTINCT tuple(tfs.name, tfs.count_value, tfs.id))
                                            )
                                    )
                                ) = 0,
                                0,
                                arrayReduce(
                                    'SUM',
                                    arrayMap(
                                        vNameAndValue ->
                                            vNameAndValue.2,
                                            arrayFilter(
                                                (pair -> pair.1 = vName),
                                                groupArray(DISTINCT tuple(tfs.name, tfs.total_value, tfs.id))
                                            )
                                    )
                                ) / arrayReduce(
                                    'SUM',
                                    arrayMap(
                                        vNameAndValue ->
                                            vNameAndValue.2,
                                            arrayFilter(
                                                (pair -> pair.1 = vName),
                                                groupArray(DISTINCT tuple(tfs.name, tfs.count_value, tfs.id))
                                            )
                                    )
                                )
                            )
                        ),
                        arrayDistinct(arrayMap(vName -> vName.1, arrayFilter(curName -> length(curName.1) > 0, groupArray(tuple(tfs.name)))))
                    ),
                    []
                ) as feedback_scores,
                count (DISTINCT ei.trace_id) as trace_count
            FROM (
                SELECT
                    *
                FROM experiments
                WHERE workspace_id = :workspace_id
                <if(dataset_id)> AND dataset_id = :dataset_id <endif>
                <if(name)> AND ilike(name, CONCAT('%', :name, '%')) <endif>
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS e
            LEFT JOIN (
                SELECT
                    experiment_id,
                    trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS ei ON e.id = ei.experiment_id
            LEFT JOIN (
                SELECT
                    t.id,
                    fs.name,
                    SUM(value) as total_value,
                    COUNT(value) as count_value
                FROM (
                    SELECT
                        id
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    ORDER BY id DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS t
                INNER JOIN (
                    SELECT
                        entity_id,
                        name,
                        value
                    FROM feedback_scores
                    WHERE entity_type = :entity_type
                    AND workspace_id = :workspace_id
                    ORDER BY entity_id DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                ) AS fs ON t.id = fs.entity_id
                GROUP BY
                    t.id,
                    fs.name
            ) AS tfs ON ei.trace_id = tfs.id
            GROUP BY
                e.workspace_id,
                e.dataset_id,
                e.id,
                e.name,
                e.metadata as metadata,
                e.created_at,
                e.last_updated_at,
                e.created_by,
                e.last_updated_by
            ORDER BY e.id DESC
            LIMIT :limit OFFSET :offset
            ;
            """;

    private static final String FIND_COUNT = """
            SELECT count(id) as count
            FROM
            (
                SELECT id
                FROM experiments
                WHERE workspace_id = :workspace_id
                <if(dataset_id)> AND dataset_id = :dataset_id <endif>
                <if(name)> AND ilike(name, CONCAT('%', :name, '%')) <endif>
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) as latest_rows
            ;
            """;

    private static final String FIND_BY_NAME = """
            SELECT
                *,
                null AS feedback_scores,
                null AS trace_count
            FROM experiments
            WHERE workspace_id = :workspace_id
            AND ilike(name, CONCAT('%', :name, '%'))
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1 BY id
            """;

    private static final String FIND_EXPERIMENT_AND_WORKSPACE_BY_DATASET_IDS = """
            SELECT
                id, workspace_id
            FROM experiments
            WHERE id in :experiment_ids
            ORDER BY last_updated_at DESC
            LIMIT 1 BY id
            ;
            """;

    private static final String DELETE_BY_IDS = """
            DELETE FROM experiments
            WHERE id IN :ids
            AND workspace_id = :workspace_id
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    Mono<Void> insert(@NonNull Experiment experiment) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> insert(experiment, connection))
                .then();
    }

    private Publisher<? extends Result> insert(Experiment experiment, Connection connection) {
        var statement = connection.createStatement(INSERT)
                .bind("id", experiment.id())
                .bind("dataset_id", experiment.datasetId())
                .bind("name", experiment.name())
                .bind("metadata", getOrDefault(experiment.metadata()));
        return makeFluxContextAware((userName, workspaceName, workspaceId) -> {
            log.info("Inserting experiment with id '{}', datasetId '{}', datasetName '{}', workspaceId '{}'",
                    experiment.id(), experiment.datasetId(), experiment.datasetName(), workspaceId);
            statement.bind("created_by", userName)
                    .bind("last_updated_by", userName)
                    .bind("workspace_id", workspaceId);
            return Flux.from(statement.execute());
        });
    }

    private String getOrDefault(JsonNode jsonNode) {
        return Optional.ofNullable(jsonNode).map(JsonNode::toString).orElse("");
    }

    Mono<Experiment> getById(@NonNull UUID id) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> getById(id, connection))
                .flatMap(this::mapToDto)
                .singleOrEmpty();
    }

    private Publisher<? extends Result> getById(UUID id, Connection connection) {
        log.info("Getting experiment by id '{}'", id);
        var statement = connection.createStatement(SELECT_BY_ID)
                .bind("id", id)
                .bind("entity_type", FeedbackScoreDAO.EntityType.TRACE.getType());
        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    private Publisher<Experiment> mapToDto(Result result) {
        return result.map((row, rowMetadata) -> Experiment.builder()
                .id(row.get("id", UUID.class))
                .datasetId(row.get("dataset_id", UUID.class))
                .name(row.get("name", String.class))
                .metadata(getOrDefault(row.get("metadata", String.class)))
                .createdAt(row.get("created_at", Instant.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .feedbackScores(getFeedbackScores(row))
                .traceCount(row.get("trace_count", Long.class))
                .build());
    }

    private JsonNode getOrDefault(String field) {
        return Optional.ofNullable(field)
                .filter(s -> !s.isBlank())
                .map(JsonUtils::getJsonNodeFromString)
                .orElse(null);
    }

    private static List<FeedbackScoreAverage> getFeedbackScores(Row row) {
        List<FeedbackScoreAverage> feedbackScoresAvg = Arrays
                .stream(Optional.ofNullable(row.get("feedback_scores", List[].class))
                        .orElse(new List[0]))
                .filter(scores -> CollectionUtils.isNotEmpty(scores) && scores.size() == 2
                        && !scores.get(1).toString().isBlank())
                .map(scores -> new FeedbackScoreAverage(scores.getFirst().toString(),
                        new BigDecimal(scores.get(1).toString())))
                .toList();
        return feedbackScoresAvg.isEmpty() ? null : feedbackScoresAvg;
    }

    Mono<Experiment.ExperimentPage> find(
            int page, int size, @NonNull ExperimentSearchCriteria experimentSearchCriteria) {
        return countTotal(experimentSearchCriteria).flatMap(total -> find(page, size, experimentSearchCriteria, total));
    }

    private Mono<Experiment.ExperimentPage> find(
            int page, int size, ExperimentSearchCriteria experimentSearchCriteria, Long total) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> find(page, size, experimentSearchCriteria, connection))
                .flatMap(this::mapToDto)
                .collectList()
                .map(experiments -> new Experiment.ExperimentPage(page, experiments.size(), total, experiments));
    }

    private Publisher<? extends Result> find(
            int page, int size, ExperimentSearchCriteria experimentSearchCriteria, Connection connection) {
        log.info("Finding experiments by '{}', page '{}', size '{}'", experimentSearchCriteria, page, size);
        var template = newFindTemplate(FIND, experimentSearchCriteria);
        var statement = connection.createStatement(template.render())
                .bind("limit", size)
                .bind("offset", (page - 1) * size);
        bindSearchCriteria(statement, experimentSearchCriteria, false);
        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    private Mono<Long> countTotal(ExperimentSearchCriteria experimentSearchCriteria) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> countTotal(experimentSearchCriteria, connection))
                .flatMap(result -> result.map((row, rowMetadata) -> row.get("count", Long.class)))
                .reduce(0L, Long::sum);
    }

    private Publisher<? extends Result> countTotal(
            ExperimentSearchCriteria experimentSearchCriteria, Connection connection) {
        log.info("Counting experiments by '{}'", experimentSearchCriteria);
        var template = newFindTemplate(FIND_COUNT, experimentSearchCriteria);
        var statement = connection.createStatement(template.render());
        bindSearchCriteria(statement, experimentSearchCriteria, true);
        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    private ST newFindTemplate(String query, ExperimentSearchCriteria criteria) {
        var template = new ST(query);
        Optional.ofNullable(criteria.datasetId())
                .ifPresent(datasetId -> template.add("dataset_id", datasetId));
        Optional.ofNullable(criteria.name())
                .ifPresent(name -> template.add("name", name));
        return template;
    }

    private void bindSearchCriteria(Statement statement, ExperimentSearchCriteria criteria, boolean isCount) {
        Optional.ofNullable(criteria.datasetId())
                .ifPresent(datasetId -> statement.bind("dataset_id", datasetId));
        Optional.ofNullable(criteria.name())
                .ifPresent(name -> statement.bind("name", name));
        if (!isCount) {
            statement.bind("entity_type", criteria.entityType().getType());
        }
    }

    Flux<Experiment> findByName(String name) {
        Preconditions.checkArgument(StringUtils.isNotBlank(name), "Argument 'name' must not be blank");
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> findByName(name, connection))
                .flatMap(this::mapToDto);
    }

    private Publisher<? extends Result> findByName(String name, Connection connection) {
        log.info("Finding experiment by name '{}'", name);
        var statement = connection.createStatement(FIND_BY_NAME).bind("name", name);
        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    public Flux<WorkspaceAndResourceId> getExperimentWorkspaces(@NonNull Set<UUID> experimentIds) {
        if (experimentIds.isEmpty()) {
            return Flux.empty();
        }
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(FIND_EXPERIMENT_AND_WORKSPACE_BY_DATASET_IDS);
                    statement.bind("experiment_ids", experimentIds.toArray(UUID[]::new));
                    return statement.execute();
                })
                .flatMap(result -> result.map((row, rowMetadata) -> new WorkspaceAndResourceId(
                        row.get("workspace_id", String.class),
                        row.get("id", UUID.class))));
    }

    public Mono<Long> delete(Set<UUID> ids) {

        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");

        log.info("Deleting experiments by ids [{}]", Arrays.toString(ids.toArray()));

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> delete(ids, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(Long::sum)
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("Deleted experiments by ids [{}]", Arrays.toString(ids.toArray()));
                    }
                });
    }

    private Flux<? extends Result> delete(Set<UUID> ids, Connection connection) {

        var statement = connection.createStatement(DELETE_BY_IDS)
                .bind("ids", ids.toArray(UUID[]::new));

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }
}
