package com.comet.opik.domain;

import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.DatasetCriteria;
import com.comet.opik.api.DatasetLastExperimentCreated;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.api.ExperimentStreamRequest;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.api.sorting.ExperimentSortingFactory;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.google.common.base.Function;
import com.google.common.base.Preconditions;
import io.opentelemetry.instrumentation.annotations.WithSpan;
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
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.Experiment.ExperimentPage;
import static com.comet.opik.api.Experiment.PromptVersionLink;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.CommentResultMapper.getComments;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.JsonUtils.getJsonNodeOrDefault;
import static com.comet.opik.utils.JsonUtils.getStringOrDefault;
import static com.comet.opik.utils.ValidationUtils.SCALE;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

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
                last_updated_by,
                prompt_version_id,
                prompt_id,
                prompt_versions,
                type,
                optimization_id
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
                new.last_updated_by,
                new.prompt_version_id,
                new.prompt_id,
                new.prompt_versions,
                new.type,
                new.optimization_id
            FROM (
                SELECT
                :id AS id,
                :dataset_id AS dataset_id,
                :name AS name,
                :workspace_id AS workspace_id,
                :metadata AS metadata,
                :created_by AS created_by,
                :last_updated_by AS last_updated_by,
                :prompt_version_id AS prompt_version_id,
                :prompt_id AS prompt_id,
                mapFromArrays(:prompt_ids, :prompt_version_ids) AS prompt_versions,
                :type AS type,
                :optimization_id AS optimization_id
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
            WITH experiments_final AS (
                SELECT
                    *
                FROM experiments
                WHERE workspace_id = :workspace_id
                <if(dataset_id)> AND dataset_id = :dataset_id <endif>
                <if(optimization_id)> AND optimization_id = :optimization_id <endif>
                <if(types)> AND type IN :types <endif>
                <if(name)> AND ilike(name, CONCAT('%', :name, '%')) <endif>
                <if(dataset_ids)> AND dataset_id IN :dataset_ids <endif>
                <if(id)> AND id = :id <endif>
                <if(lastRetrievedId)> AND id \\< :lastRetrievedId <endif>
                <if(prompt_ids)>AND (prompt_id IN :prompt_ids OR hasAny(mapKeys(prompt_versions), :prompt_ids))<endif>
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), experiment_items_final AS (
                SELECT
                    id, experiment_id, trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id IN (SELECT id FROM experiments_final)
            ), trace_final AS (
                SELECT
                    id
                FROM traces
                WHERE workspace_id = :workspace_id
            ), experiment_durations AS (
                SELECT
                    experiment_id,
                    arrayMap(v -> toDecimal64(if(isNaN(v), 0, v), 9), quantiles(0.5, 0.9, 0.99)(duration)) AS duration_values,
                    count(DISTINCT trace_id) as trace_count,
                    avgMap(usage) as usage,
                    avg(total_estimated_cost) as total_estimated_cost
                FROM (
                    SELECT DISTINCT
                        ei.experiment_id,
                        ei.trace_id as trace_id,
                        t.duration as duration,
                        usage,
                        total_estimated_cost
                    FROM experiment_items_final ei
                    LEFT JOIN (
                        SELECT
                            id,
                            if(end_time IS NOT NULL AND start_time IS NOT NULL
                                AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                                (dateDiff('microsecond', start_time, end_time) / 1000.0),
                                NULL) as duration
                        FROM traces final
                        WHERE workspace_id = :workspace_id
                        AND id IN (SELECT trace_id FROM experiment_items_final)
                    ) AS t ON ei.trace_id = t.id
                    LEFT JOIN (
                        SELECT
                            trace_id,
                            sumMap(usage) as usage,
                            sum(total_estimated_cost) as total_estimated_cost
                        FROM spans final
                        WHERE workspace_id = :workspace_id
                        GROUP BY workspace_id, project_id, trace_id
                    ) AS s ON ei.trace_id = s.trace_id
                )
                GROUP BY experiment_id
            ),
            feedback_scores_agg AS (
                SELECT
                    experiment_id,
                    mapFromArrays(
                        groupArray(fs_avg.name),
                        groupArray(fs_avg.avg_value)
                    ) AS feedback_scores
                FROM (
                    SELECT
                        et.experiment_id,
                        fs.name,
                        avg(fs.value) AS avg_value
                    FROM (
                        SELECT
                            DISTINCT
                                experiment_id,
                                trace_id
                        FROM experiment_items_final
                    ) as et
                    LEFT JOIN (
                        SELECT
                            name,
                            entity_id AS trace_id,
                            value
                        FROM feedback_scores final
                        WHERE workspace_id = :workspace_id
                        AND entity_type = 'trace'
                        AND entity_id IN (SELECT trace_id FROM experiment_items_final)
                    ) fs ON fs.trace_id = et.trace_id
                    GROUP BY et.experiment_id, fs.name
                    HAVING length(fs.name) > 0
                ) as fs_avg
                GROUP BY experiment_id
            ),
            comments_agg AS (
                SELECT
                    ei.experiment_id,
                    groupUniqArrayArray(tc.comments_array) as comments_array_agg
                FROM experiment_items ei
                LEFT JOIN (
                    SELECT
                        entity_id,
                        groupArray(tuple(*)) AS comments_array
                    FROM (
                        SELECT
                            id,
                            text,
                            created_at,
                            last_updated_at,
                            created_by,
                            last_updated_by,
                            entity_id
                        FROM comments
                        WHERE workspace_id = :workspace_id
                        AND entity_type = :entity_type
                        ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY id
                    )
                    GROUP BY entity_id
                ) AS tc ON ei.trace_id = tc.entity_id
                WHERE ei.trace_id IN (SELECT id FROM trace_final)
                GROUP BY ei.experiment_id
            )
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
                e.prompt_version_id as prompt_version_id,
                e.prompt_id as prompt_id,
                e.prompt_versions as prompt_versions,
                e.optimization_id as optimization_id,
                e.type as type,
                fs.feedback_scores as feedback_scores,
                ed.trace_count as trace_count,
                ed.duration_values AS duration,
                ed.usage as usage,
                ed.total_estimated_cost as total_estimated_cost,
                ca.comments_array_agg as comments_array_agg
            FROM experiments_final AS e
            LEFT JOIN experiment_durations AS ed ON e.id = ed.experiment_id
            LEFT JOIN feedback_scores_agg AS fs ON e.id = fs.experiment_id
            LEFT JOIN comments_agg AS ca ON e.id = ca.experiment_id
            ORDER BY <if(sort_fields)><sort_fields>,<endif> e.id DESC
            <if(limit)> LIMIT :limit <endif> <if(offset)> OFFSET :offset <endif>
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
                <if(optimization_id)> AND optimization_id = :optimization_id <endif>
                <if(types)> AND type IN :types <endif>
                <if(name)> AND ilike(name, CONCAT('%', :name, '%')) <endif>
                <if(dataset_ids)> AND dataset_id IN :dataset_ids <endif>
                <if(prompt_ids)>AND (prompt_id IN :prompt_ids OR hasAny(mapKeys(prompt_versions), :prompt_ids))<endif>
                ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) as latest_rows
            ;
            """;

    private static final String FIND_BY_NAME = """
            SELECT
                *,
                null AS feedback_scores,
                null AS trace_count,
                null AS duration,
                null AS total_estimated_cost,
                null AS usage,
                null AS comments_array_agg
            FROM experiments
            WHERE workspace_id = :workspace_id
            AND ilike(name, CONCAT('%', :name, '%'))
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1 BY id
            """;

    private static final String FIND_EXPERIMENT_AND_WORKSPACE_BY_EXPERIMENT_IDS = """
            SELECT
                DISTINCT id, workspace_id
            FROM experiments
            WHERE id in :experiment_ids
            ;
            """;

    private static final String DELETE_BY_IDS = """
            DELETE FROM experiments
            WHERE id IN :ids
            AND workspace_id = :workspace_id
            ;
            """;
    private static final String FIND_MOST_RECENT_CREATED_EXPERIMENT_BY_DATASET_IDS = """
            SELECT
            	dataset_id,
            	max(created_at) as created_at
            FROM (
                SELECT
                    id,
                    dataset_id,
                    created_at
                FROM experiments
                WHERE dataset_id IN :dataset_ids
            	AND workspace_id = :workspace_id
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            GROUP BY dataset_id;
            ;
            """;

    private static final String FIND_EXPERIMENT_DATASET_ID_EXPERIMENT_IDS = """
            SELECT
                distinct dataset_id, type
            FROM experiments
            WHERE workspace_id = :workspace_id
            <if(experiment_ids)> AND id IN :experiment_ids <endif>
            <if(prompt_ids)>AND (prompt_id IN :prompt_ids OR hasAny(mapKeys(prompt_versions), :prompt_ids))<endif>
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1 BY id
            ;
            """;

    private static final String EXPERIMENT_DAILY_BI_INFORMATION = """
            SELECT
                 workspace_id,
                 created_by AS user,
                 COUNT(DISTINCT id) AS experiment_count
            FROM experiments
            WHERE created_at BETWEEN toStartOfDay(yesterday()) AND toStartOfDay(today())
            AND id NOT IN (
                SELECT id
                FROM experiments
                WHERE workspace_id = :demo_workspace_id
                AND name IN :excluded_names
            )
            GROUP BY workspace_id, created_by
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull ExperimentSortingFactory sortingFactory;

    @WithSpan
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
                .bind("metadata", getStringOrDefault(experiment.metadata()))
                .bind("type", Optional.ofNullable(experiment.type()).orElse(ExperimentType.REGULAR).getValue())
                .bind("optimization_id", experiment.optimizationId() != null ? experiment.optimizationId() : "");

        if (experiment.promptVersion() != null) {
            statement.bind("prompt_version_id", experiment.promptVersion().id());
            statement.bind("prompt_id", experiment.promptVersion().promptId());
        } else {
            statement.bindNull("prompt_version_id", UUID.class);
            statement.bindNull("prompt_id", UUID.class);
        }

        if (experiment.promptVersions() != null) {

            var versionMap = experiment.promptVersions()
                    .stream()
                    .collect(groupingBy(PromptVersionLink::promptId, mapping(PromptVersionLink::id, toList())));

            UUID[][] values = versionMap.keySet().stream()
                    .map(versionMap::get)
                    .map(ids -> ids.toArray(UUID[]::new))
                    .toArray(UUID[][]::new);

            statement.bind("prompt_ids", versionMap.keySet().toArray(UUID[]::new));
            statement.bind("prompt_version_ids", values);
        } else {
            statement.bind("prompt_ids", new UUID[]{});
            statement.bind("prompt_version_ids", new UUID[]{});
        }

        return makeFluxContextAware((userName, workspaceId) -> {
            log.info("Inserting experiment with id '{}', datasetId '{}', datasetName '{}', workspaceId '{}'",
                    experiment.id(), experiment.datasetId(), experiment.datasetName(), workspaceId);
            statement.bind("created_by", userName)
                    .bind("last_updated_by", userName)
                    .bind("workspace_id", workspaceId);
            return Flux.from(statement.execute());
        });
    }

    @WithSpan
    Mono<Experiment> getById(@NonNull UUID id) {
        log.info("Getting experiment by id '{}'", id);
        var limit = 1;
        var template = new ST(FIND);
        template.add("id", id.toString());
        template.add("limit", limit);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> get(
                        template.render(), connection,
                        statement -> statement.bind("id", id).bind("limit", limit)))
                .flatMap(this::mapToDto)
                .singleOrEmpty();
    }

    @WithSpan
    Flux<Experiment> get(@NonNull ExperimentStreamRequest request) {
        log.info("Getting experiment by '{}'", request);
        var template = new ST(FIND);
        template.add("name", request.name());
        if (request.lastRetrievedId() != null) {
            template.add("lastRetrievedId", request.lastRetrievedId());
        }
        template.add("limit", request.limit());
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> get(
                        template.render(), connection,
                        statement -> {
                            statement.bind("name", request.name());
                            if (request.lastRetrievedId() != null) {
                                statement = statement.bind("lastRetrievedId", request.lastRetrievedId());
                            }
                            return statement.bind("limit", request.limit());
                        }))
                .flatMap(this::mapToDto);
    }

    private Publisher<? extends Result> get(String query, Connection connection, Function<Statement, Statement> bind) {
        var statement = connection.createStatement(query)
                .bind("entity_type", EntityType.TRACE.getType());
        return makeFluxContextAware(bindWorkspaceIdToFlux(bind.apply(statement)));
    }

    private Publisher<Experiment> mapToDto(Result result) {
        return result.map((row, rowMetadata) -> {
            List<PromptVersionLink> promptVersions = getPromptVersions(row);
            return Experiment.builder()
                    .id(row.get("id", UUID.class))
                    .datasetId(row.get("dataset_id", UUID.class))
                    .name(row.get("name", String.class))
                    .metadata(getJsonNodeOrDefault(row.get("metadata", String.class)))
                    .createdAt(row.get("created_at", Instant.class))
                    .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                    .createdBy(row.get("created_by", String.class))
                    .lastUpdatedBy(row.get("last_updated_by", String.class))
                    .feedbackScores(getFeedbackScores(row))
                    .comments(getComments(row.get("comments_array_agg", List[].class)))
                    .traceCount(row.get("trace_count", Long.class))
                    .duration(getDuration(row))
                    .totalEstimatedCost(getTotalEstimatedCost(row))
                    .usage(row.get("usage", Map.class))
                    .promptVersion(promptVersions.stream().findFirst().orElse(null))
                    .promptVersions(promptVersions.isEmpty() ? null : promptVersions)
                    .optimizationId(Optional.ofNullable(row.get("optimization_id", String.class))
                            .filter(str -> !str.isBlank())
                            .map(UUID::fromString)
                            .orElse(null))
                    .type(ExperimentType.fromString(row.get("type", String.class)))
                    .build();
        });
    }

    private static BigDecimal getTotalEstimatedCost(Row row) {
        return Optional.ofNullable(row.get("total_estimated_cost", BigDecimal.class))
                .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                .orElse(null);
    }

    private static PercentageValues getDuration(Row row) {
        return Optional.ofNullable(row.get("duration", List.class))
                .filter(value -> !value.isEmpty()
                        && value.stream().anyMatch(it -> BigDecimal.ZERO.compareTo((BigDecimal) it) < 0))
                .map(durations -> new PercentageValues(
                        getP(durations, 0),
                        getP(durations, 1),
                        getP(durations, 2)))
                .orElse(null);
    }

    private static BigDecimal getP(List<BigDecimal> durations, int index) {
        return durations.get(index);
    }

    private List<PromptVersionLink> getPromptVersions(Row row) {
        Map<String, String[]> promptVersions = row.get("prompt_versions", Map.class);
        Optional<PromptVersionLink> promptVersion = Optional.ofNullable(row.get("prompt_version_id", UUID.class))
                .map(id -> PromptVersionLink.builder().promptId(row.get("prompt_id", UUID.class)).id(id).build());

        if (MapUtils.isEmpty(promptVersions)) {
            return promptVersion.stream().toList();
        }

        return Stream.concat(
                promptVersion.stream(),
                promptVersions.entrySet()
                        .stream()
                        .flatMap(entry -> Arrays.stream(entry.getValue())
                                .map(UUID::fromString)
                                .map(promptVersionId -> PromptVersionLink.builder()
                                        .promptId(UUID.fromString(entry.getKey()))
                                        .id(promptVersionId)
                                        .build())))
                .distinct()
                .toList();
    }

    public static List<FeedbackScoreAverage> getFeedbackScores(Row row) {
        List<FeedbackScoreAverage> feedbackScoresAvg = Optional
                .ofNullable(row.get("feedback_scores", Map.class))
                .map(map -> (Map<String, ? extends Number>) map)
                .orElse(Map.of())
                .entrySet()
                .stream()
                .map(scores -> {
                    return new FeedbackScoreAverage(scores.getKey(),
                            BigDecimal.valueOf(scores.getValue().doubleValue()).setScale(SCALE,
                                    RoundingMode.HALF_EVEN));
                })
                .toList();

        return feedbackScoresAvg.isEmpty() ? null : feedbackScoresAvg;
    }

    @WithSpan
    Mono<ExperimentPage> find(
            int page, int size, @NonNull ExperimentSearchCriteria experimentSearchCriteria) {
        return countTotal(experimentSearchCriteria).flatMap(total -> find(page, size, experimentSearchCriteria, total));
    }

    private Mono<ExperimentPage> find(
            int page, int size, ExperimentSearchCriteria experimentSearchCriteria, Long total) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> find(page, size, experimentSearchCriteria, connection))
                .flatMap(this::mapToDto)
                .collectList()
                .map(experiments -> new ExperimentPage(page, experiments.size(), total, experiments,
                        sortingFactory.getSortableFields()));
    }

    private Publisher<? extends Result> find(
            int page, int size, ExperimentSearchCriteria experimentSearchCriteria, Connection connection) {
        log.info("Finding experiments by '{}', page '{}', size '{}'", experimentSearchCriteria, page, size);

        var sorting = sortingQueryBuilder.toOrderBySql(experimentSearchCriteria.sortingFields());

        var hasDynamicKeys = sortingQueryBuilder.hasDynamicKeys(experimentSearchCriteria.sortingFields());

        int offset = (page - 1) * size;

        var template = newFindTemplate(FIND, experimentSearchCriteria);

        template.add("sort_fields", sorting);
        template.add("limit", size);
        template.add("offset", offset);

        var statement = connection.createStatement(template.render())
                .bind("limit", size)
                .bind("offset", offset);

        if (hasDynamicKeys) {
            statement = sortingQueryBuilder.bindDynamicKeys(statement, experimentSearchCriteria.sortingFields());
        }

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
        Optional.ofNullable(criteria.datasetIds())
                .ifPresent(datasetIds -> template.add("dataset_ids", datasetIds));
        Optional.ofNullable(criteria.promptId())
                .ifPresent(promptId -> template.add("prompt_ids", promptId));
        Optional.ofNullable(criteria.optimizationId())
                .ifPresent(optimizationId -> template.add("optimization_id", optimizationId));
        Optional.ofNullable(criteria.types())
                .filter(CollectionUtils::isNotEmpty)
                .ifPresent(types -> template.add("types", types));
        return template;
    }

    private void bindSearchCriteria(Statement statement, ExperimentSearchCriteria criteria, boolean isCount) {
        Optional.ofNullable(criteria.datasetId())
                .ifPresent(datasetId -> statement.bind("dataset_id", datasetId));
        Optional.ofNullable(criteria.name())
                .ifPresent(name -> statement.bind("name", name));
        Optional.ofNullable(criteria.datasetIds())
                .ifPresent(datasetIds -> statement.bind("dataset_ids", datasetIds.toArray(UUID[]::new)));
        Optional.ofNullable(criteria.promptId())
                .ifPresent(promptId -> statement.bind("prompt_ids", List.of(promptId).toArray(UUID[]::new)));
        Optional.ofNullable(criteria.optimizationId())
                .ifPresent(optimizationId -> statement.bind("optimization_id", optimizationId));
        Optional.ofNullable(criteria.types())
                .filter(CollectionUtils::isNotEmpty)
                .ifPresent(types -> statement.bind("types", types));
        if (!isCount) {
            statement.bind("entity_type", criteria.entityType().getType());
        }
    }

    @WithSpan
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

    @WithSpan
    public Flux<WorkspaceAndResourceId> getExperimentWorkspaces(@NonNull Set<UUID> experimentIds) {
        if (experimentIds.isEmpty()) {
            return Flux.empty();
        }
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(FIND_EXPERIMENT_AND_WORKSPACE_BY_EXPERIMENT_IDS);
                    statement.bind("experiment_ids", experimentIds.toArray(UUID[]::new));
                    return statement.execute();
                })
                .flatMap(result -> result.map((row, rowMetadata) -> new WorkspaceAndResourceId(
                        row.get("workspace_id", String.class),
                        row.get("id", UUID.class))));
    }

    @WithSpan
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

    @WithSpan
    Flux<BiInformationResponse.BiInformation> getExperimentBIInformation() {
        return Mono.from(connectionFactory.create())
                .flatMapMany(this::getBiDailyData)
                .flatMap(result -> result.map((row, rowMetadata) -> BiInformationResponse.BiInformation.builder()
                        .workspaceId(row.get("workspace_id", String.class))
                        .user(row.get("user", String.class))
                        .count(row.get("experiment_count", Long.class)).build()));
    }

    private Publisher<? extends Result> getBiDailyData(Connection connection) {
        return connection.createStatement(EXPERIMENT_DAILY_BI_INFORMATION)
                .bind("demo_workspace_id", ProjectService.DEFAULT_WORKSPACE_ID)
                .bind("excluded_names", DemoData.EXPERIMENTS)
                .execute();
    }

    private Flux<? extends Result> delete(Set<UUID> ids, Connection connection) {

        var statement = connection.createStatement(DELETE_BY_IDS)
                .bind("ids", ids.toArray(UUID[]::new));

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    @WithSpan
    public Flux<DatasetLastExperimentCreated> getMostRecentCreatedExperimentFromDatasets(Set<UUID> datasetIds) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(datasetIds), "Argument 'datasetIds' must not be empty");

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(FIND_MOST_RECENT_CREATED_EXPERIMENT_BY_DATASET_IDS);
                    statement.bind("dataset_ids", datasetIds.toArray(UUID[]::new));
                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, rowMetadata) -> new DatasetLastExperimentCreated(
                        row.get("dataset_id", UUID.class),
                        row.get("created_at", Instant.class))));
    }

    @WithSpan
    public Mono<List<DatasetEventInfoHolder>> getExperimentsDatasetInfo(Set<UUID> ids) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    ST template = new ST(FIND_EXPERIMENT_DATASET_ID_EXPERIMENT_IDS);
                    template.add("experiment_ids", ids);
                    var statement = connection.createStatement(template.render());
                    statement.bind("experiment_ids", ids.toArray(UUID[]::new));
                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(this::mapDatasetInfo)
                .collectList();
    }

    @WithSpan
    public Mono<List<DatasetEventInfoHolder>> findAllDatasetIds(@NonNull DatasetCriteria criteria) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    ST template = new ST(FIND_EXPERIMENT_DATASET_ID_EXPERIMENT_IDS);

                    bindFindAllDatasetIdsTemplateParams(criteria, template);

                    var statement = connection.createStatement(template.render());

                    bindFindAllDatasetIdsParams(criteria, statement);

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(this::mapDatasetInfo)
                .collectList();
    }

    private void bindFindAllDatasetIdsTemplateParams(DatasetCriteria criteria, ST template) {
        if (criteria.promptId() != null) {
            template.add("prompt_ids", criteria.promptId());
        }
    }

    private void bindFindAllDatasetIdsParams(DatasetCriteria criteria, Statement statement) {
        if (criteria.promptId() != null) {
            statement.bind("prompt_ids", List.of(criteria.promptId()).toArray(UUID[]::new));
        }
    }

    private Publisher<DatasetEventInfoHolder> mapDatasetInfo(Result result) {
        return result.map((row, rowMetadata) -> new DatasetEventInfoHolder(row.get("dataset_id", UUID.class),
                ExperimentType.fromString(row.get("type", String.class))));
    }

    public Mono<Long> getDailyCreatedCount() {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(EXPERIMENT_DAILY_BI_INFORMATION)
                        .bind("demo_workspace_id", ProjectService.DEFAULT_WORKSPACE_ID)
                        .bind("excluded_names", DemoData.EXPERIMENTS)
                        .execute())
                .flatMap(result -> result.map((row, rowMetadata) -> row.get("experiment_count", Long.class)))
                .reduce(0L, Long::sum);
    }
}
