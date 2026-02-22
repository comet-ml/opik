package com.comet.opik.domain;

import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.DatasetLastExperimentCreated;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.Experiment.ExperimentPage;
import com.comet.opik.api.Experiment.PromptVersionLink;
import com.comet.opik.api.ExperimentGroupAggregationItem;
import com.comet.opik.api.ExperimentGroupCriteria;
import com.comet.opik.api.ExperimentGroupItem;
import com.comet.opik.api.ExperimentScore;
import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentStreamRequest;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.PercentageValues;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.sorting.ExperimentSortingFactory;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.RowUtils;
import com.comet.opik.utils.template.TemplateUtils;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.CommentResultMapper.getComments;
import static com.comet.opik.infrastructure.DatabaseUtils.getSTWithLogComment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.JsonUtils.getJsonNodeOrDefault;
import static com.comet.opik.utils.JsonUtils.getStringOrDefault;
import static com.comet.opik.utils.ValidationUtils.SCALE;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toSet;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class ExperimentDAO {

    /**
     * Common parameters for target project IDs query.
     * Used to reduce traces, spans, and feedback_scores table scans.
     */
    private record TargetProjectsCriteria(
            UUID datasetId,
            String name,
            Collection<UUID> datasetIds,
            UUID promptId,
            UUID optimizationId,
            Set<ExperimentType> types,
            Set<UUID> experimentIds,
            List<? extends Filter> filters,
            Boolean projectDeleted) {

        static TargetProjectsCriteria from(ExperimentGroupCriteria criteria) {
            return new TargetProjectsCriteria(
                    null,
                    criteria.name(),
                    null,
                    null,
                    null,
                    criteria.types(),
                    null,
                    criteria.filters(),
                    criteria.projectDeleted());
        }

        static TargetProjectsCriteria from(ExperimentSearchCriteria criteria) {
            return new TargetProjectsCriteria(
                    criteria.datasetId(),
                    criteria.name(),
                    criteria.datasetIds(),
                    criteria.promptId(),
                    criteria.optimizationId(),
                    criteria.types(),
                    criteria.experimentIds(),
                    criteria.filters(),
                    criteria.projectDeleted());
        }

        /**
         * Check if optimization should be skipped.
         * Skip when filtering by projectDeleted=true, because we're specifically looking for
         * experiments with deleted/missing projects. The optimization would find project IDs
         * from experiments with valid projects and incorrectly filter out the experiments we're looking for.
         */
        boolean shouldSkipOptimization() {
            return Boolean.TRUE.equals(projectDeleted);
        }
    }

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
                tags,
                created_by,
                last_updated_by,
                prompt_version_id,
                prompt_id,
                prompt_versions,
                type,
                optimization_id,
                status,
                experiment_scores,
                dataset_version_id
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
                new.tags,
                new.created_by,
                new.last_updated_by,
                new.prompt_version_id,
                new.prompt_id,
                new.prompt_versions,
                new.type,
                new.optimization_id,
                new.status,
                new.experiment_scores,
                new.dataset_version_id
            FROM (
                SELECT
                :id AS id,
                :dataset_id AS dataset_id,
                :name AS name,
                :workspace_id AS workspace_id,
                :metadata AS metadata,
                :tags AS tags,
                :created_by AS created_by,
                :last_updated_by AS last_updated_by,
                :prompt_version_id AS prompt_version_id,
                :prompt_id AS prompt_id,
                mapFromArrays(:prompt_ids, :prompt_version_ids) AS prompt_versions,
                :type AS type,
                :optimization_id AS optimization_id,
                :status AS status,
                :experiment_scores AS experiment_scores,
                :dataset_version_id AS dataset_version_id
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
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String FIND = """
            WITH experiments_final AS (
                SELECT
                    *, arrayConcat([prompt_id], mapKeys(prompt_versions)) AS prompt_ids
                FROM experiments FINAL
                WHERE workspace_id = :workspace_id
                <if(dataset_id)> AND dataset_id = :dataset_id <endif>
                <if(optimization_id)> AND optimization_id = :optimization_id <endif>
                <if(types)> AND type IN :types <endif>
                <if(name)> AND ilike(name, CONCAT('%', :name, '%')) <endif>
                <if(dataset_ids)> AND dataset_id IN :dataset_ids <endif>
                <if(id)> AND id = :id <endif>
                <if(ids_list)> AND id IN :ids_list <endif>
                <if(experiment_ids)> AND id IN :experiment_ids <endif>
                <if(lastRetrievedId)> AND id \\< :lastRetrievedId <endif>
                <if(prompt_ids)>AND hasAny(prompt_ids, :prompt_ids)<endif>
                <if(filters)> AND <filters> <endif>
                ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                <if(limit &&
                !feedback_scores_filters &&
                !feedback_scores_empty_filters &&
                !experiment_scores_filters &&
                !experiment_scores_empty_filters &&
                !project_id &&
                !project_deleted &&
                !sort_fields
                )>
                LIMIT :limit <if(offset)> OFFSET :offset <endif>
                <endif>
            ), experiment_items_final AS (
                SELECT DISTINCT
                    id, experiment_id, trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id IN (SELECT id FROM experiments_final)
            ), experiment_durations AS (
                SELECT
                    experiment_id,
                    groupUniqArray(project_id) AS project_ids,
                    mapFromArrays(
                        ['p50', 'p90', 'p99'],
                        arrayMap(
                          v -> toDecimal64(
                                 greatest(
                                   least(if(isFinite(v), v, 0),  999999999.999999999),
                                   -999999999.999999999
                                 ),
                                 9
                               ),
                          quantiles(0.5, 0.9, 0.99)(duration)
                        )
                    ) AS duration_values,
                    count(DISTINCT ei.trace_id) as trace_count,
                    avgMap(usage) as usage,
                    sum(total_estimated_cost) as total_estimated_cost_sum,
                    avg(total_estimated_cost) as total_estimated_cost_avg
                FROM experiment_items_final ei
                LEFT JOIN (
                    SELECT
                        id,
                        duration,
                        project_id
                    FROM traces final
                    WHERE workspace_id = :workspace_id
                    <if(has_target_projects)>
                    AND project_id IN :target_project_ids
                    <else>
                    AND id IN (SELECT trace_id FROM experiment_items_final)
                    <endif>
                ) AS t ON ei.trace_id = t.id
                LEFT JOIN (
                    SELECT
                        trace_id,
                        sumMap(usage) as usage,
                        sum(total_estimated_cost) as total_estimated_cost
                    FROM spans final
                    WHERE workspace_id = :workspace_id
                    <if(has_target_projects)>
                    AND project_id IN :target_project_ids
                    <else>
                    AND trace_id IN (SELECT trace_id FROM experiment_items_final)
                    <endif>
                    GROUP BY workspace_id, project_id, trace_id
                ) AS s ON t.id = s.trace_id
                GROUP BY experiment_id
            ), feedback_scores_combined_raw AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       feedback_scores.last_updated_by AS author
                FROM feedback_scores FINAL
                WHERE entity_type = 'trace'
                AND workspace_id = :workspace_id
                <if(has_target_projects)>
                AND project_id IN :target_project_ids
                <else>
                AND entity_id IN (SELECT trace_id FROM experiment_items_final)
                <endif>
                UNION ALL
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    value,
                    last_updated_at,
                    author
                FROM authored_feedback_scores FINAL
                WHERE entity_type = 'trace'
                AND workspace_id = :workspace_id
                <if(has_target_projects)>
                AND project_id IN :target_project_ids
                <else>
                AND entity_id IN (SELECT trace_id FROM experiment_items_final)
                <endif>
            ), feedback_scores_with_ranking AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author,
                       ROW_NUMBER() OVER (
                           PARTITION BY workspace_id, project_id, entity_id, name, author
                           ORDER BY last_updated_at DESC
                       ) as rn
                FROM feedback_scores_combined_raw
            ), feedback_scores_combined AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author
                FROM feedback_scores_with_ranking
                WHERE rn = 1
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value,
                    max(last_updated_at) AS last_updated_at
                FROM feedback_scores_combined
                GROUP BY workspace_id, project_id, entity_id, name
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
                    FROM experiment_items_final as et
                    INNER JOIN (
                        SELECT DISTINCT id FROM traces
                        WHERE workspace_id = :workspace_id
                        <if(has_target_projects)>AND project_id IN :target_project_ids
                        <else>
                        AND id IN (SELECT trace_id FROM experiment_items_final)
                        <endif>
                    ) AS t ON et.trace_id = t.id
                    LEFT JOIN feedback_scores_final fs ON fs.entity_id = et.trace_id
                    GROUP BY et.experiment_id, fs.name
                    HAVING length(fs.name) > 0
                ) as fs_avg
                GROUP BY experiment_id
            ),
            <if(feedback_scores_empty_filters)>
             fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM (
                    SELECT *
                    FROM feedback_scores_final
                    ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                 )
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            ),
            <endif>
            experiment_scores_final AS (
                SELECT
                    e.id AS experiment_id,
                    JSON_VALUE(score, '$.name') AS name,
                    CAST(JSON_VALUE(score, '$.value') AS Decimal(18, 9)) AS value
                FROM experiments_final AS e
                ARRAY JOIN JSONExtractArrayRaw(e.experiment_scores) AS score
                WHERE length(e.experiment_scores) > 2
                  AND length(JSON_VALUE(score, '$.name')) > 0
            ),
            <if(experiment_scores_empty_filters)>
             esc AS (SELECT experiment_id, COUNT(experiment_id) AS experiment_scores_count
                 FROM experiment_scores_final
                 GROUP BY experiment_id
                 HAVING <experiment_scores_empty_filters>
            ),
            <endif>
            experiment_scores_agg AS (
                SELECT
                    experiment_id,
                    mapFromArrays(
                        groupArray(name),
                        groupArray(value)
                    ) AS experiment_scores
                FROM experiment_scores_final
                GROUP BY experiment_id
            ),
            comments_agg AS (
                SELECT
                    ei.experiment_id,
                    groupUniqArrayArray(tc.comments_array) as comments_array_agg
                FROM experiment_items_final ei
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
                        <if(has_target_projects)>
                        AND project_id IN :target_project_ids
                        <endif>
                        ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY id
                    )
                    GROUP BY entity_id
                ) AS tc ON ei.trace_id = tc.entity_id
                GROUP BY ei.experiment_id
            )
            SELECT
                e.workspace_id as workspace_id,
                e.dataset_id as dataset_id,
                if(empty(ed.project_ids), '', ed.project_ids[1]) as project_id,
                e.id as id,
                e.name as name,
                e.metadata as metadata,
                e.tags as tags,
                e.created_at as created_at,
                e.last_updated_at as last_updated_at,
                e.created_by as created_by,
                e.last_updated_by as last_updated_by,
                e.prompt_version_id as prompt_version_id,
                e.prompt_id as prompt_id,
                e.prompt_versions as prompt_versions,
                e.optimization_id as optimization_id,
                e.type as type,
                e.status as status,
                e.experiment_scores as experiment_scores,
                e.dataset_version_id as dataset_version_id,
                fs.feedback_scores as feedback_scores,
                es.experiment_scores as experiment_scores_agg,
                ed.trace_count as trace_count,
                ed.duration_values AS duration,
                ed.usage as usage,
                ed.total_estimated_cost_sum as total_estimated_cost,
                ed.total_estimated_cost_avg as total_estimated_cost_avg,
                ca.comments_array_agg as comments_array_agg
            FROM experiments_final AS e
            LEFT JOIN experiment_durations AS ed ON e.id = ed.experiment_id
            LEFT JOIN feedback_scores_agg AS fs ON e.id = fs.experiment_id
            LEFT JOIN experiment_scores_agg AS es ON e.id = es.experiment_id
            LEFT JOIN comments_agg AS ca ON e.id = ca.experiment_id
            WHERE 1=1
            <if(feedback_scores_filters)>
            AND id in (
                SELECT DISTINCT experiment_id FROM experiment_items_final
                WHERE trace_id IN (
                    SELECT
                    entity_id AS trace_id
                    FROM (
                        SELECT *
                        FROM feedback_scores_final
                        ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                        LIMIT 1 BY entity_id, name
                    )
                    GROUP BY entity_id
                    HAVING <feedback_scores_filters>
                )
            )
            <endif>
            <if(feedback_scores_empty_filters)>
            AND id NOT IN (
               SELECT DISTINCT experiment_id FROM experiment_items_final
               WHERE trace_id IN (SELECT entity_id FROM fsc)
            )
            <endif>
            <if(experiment_scores_filters)>
            AND id IN (
                SELECT experiment_id
                FROM experiment_scores_final
                GROUP BY experiment_id
                HAVING <experiment_scores_filters>
            )
            <endif>
            <if(experiment_scores_empty_filters)>
            AND id NOT IN (SELECT experiment_id FROM esc)
            <endif>
            <if(project_id)>
            AND has(ed.project_ids, :project_id)
            <endif>
            <if(project_deleted)>
            AND (has(ed.project_ids, '') OR empty(ed.project_ids))
            <endif>
            ORDER BY <if(sort_fields)><sort_fields>,<endif> e.id DESC
            <if(limit && (
                feedback_scores_filters ||
                feedback_scores_empty_filters ||
                experiment_scores_filters ||
                experiment_scores_empty_filters ||
                project_id ||
                project_deleted ||
                sort_fields
                )
            )>
            LIMIT :limit <if(offset)> OFFSET :offset <endif>
            <endif>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String FIND_COUNT = """
            WITH experiments_initial AS (
                SELECT id, arrayConcat([prompt_id], mapKeys(prompt_versions)) AS prompt_ids, experiment_scores
                FROM experiments
                WHERE workspace_id = :workspace_id
                <if(dataset_id)> AND dataset_id = :dataset_id <endif>
                <if(optimization_id)> AND optimization_id = :optimization_id <endif>
                <if(types)> AND type IN :types <endif>
                <if(name)> AND ilike(name, CONCAT('%', :name, '%')) <endif>
                <if(dataset_ids)> AND dataset_id IN :dataset_ids <endif>
                <if(experiment_ids)> AND id IN :experiment_ids <endif>
                <if(prompt_ids)>AND hasAny(prompt_ids, :prompt_ids)<endif>
                <if(filters)> AND <filters> <endif>
                ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), experiment_items_final AS (
                SELECT
                    id, experiment_id, trace_id
                FROM experiment_items final
                WHERE workspace_id = :workspace_id
                AND experiment_id IN (SELECT id FROM experiments_initial)
            ), feedback_scores_combined_raw AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       feedback_scores.last_updated_by AS author
                FROM feedback_scores FINAL
                WHERE entity_type = 'trace'
                AND workspace_id = :workspace_id
                <if(has_target_projects)>
                AND project_id IN :target_project_ids
                <else>
                AND entity_id IN (SELECT trace_id FROM experiment_items_final)
                <endif>
                UNION ALL
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    value,
                    last_updated_at,
                    author
                FROM authored_feedback_scores FINAL
                WHERE entity_type = 'trace'
                AND workspace_id = :workspace_id
                <if(has_target_projects)>
                AND project_id IN :target_project_ids
                <else>
                AND entity_id IN (SELECT trace_id FROM experiment_items_final)
                <endif>
            ), feedback_scores_with_ranking AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author,
                       ROW_NUMBER() OVER (
                           PARTITION BY workspace_id, project_id, entity_id, name, author
                           ORDER BY last_updated_at DESC
                       ) as rn
                FROM feedback_scores_combined_raw
            ), feedback_scores_combined AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author
                FROM feedback_scores_with_ranking
                WHERE rn = 1
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    if(count() = 1, any(value), toDecimal64(avg(value), 9)) AS value,
                    max(last_updated_at) AS last_updated_at
                FROM feedback_scores_combined
                GROUP BY workspace_id, project_id, entity_id, name
            )
            <if(feedback_scores_empty_filters)>
             , fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM (
                    SELECT *
                    FROM feedback_scores_final
                    ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                    LIMIT 1 BY entity_id, name
                 )
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            )
            <endif>
            <if(experiment_scores_filters || experiment_scores_empty_filters)>
             , experiment_scores_final AS (
                SELECT
                    e.id AS experiment_id,
                    JSON_VALUE(score, '$.name') AS name,
                    CAST(JSON_VALUE(score, '$.value') AS Decimal(18, 9)) AS value
                FROM experiments_initial AS e
                ARRAY JOIN JSONExtractArrayRaw(e.experiment_scores) AS score
                WHERE length(e.experiment_scores) > 2
                  AND length(JSON_VALUE(score, '$.name')) > 0
            )
            <endif>
            <if(experiment_scores_empty_filters)>
             , esc AS (SELECT experiment_id, COUNT(experiment_id) AS experiment_scores_count
                 FROM experiment_scores_final
                 GROUP BY experiment_id
                 HAVING <experiment_scores_empty_filters>
            )
            <endif>
            <if(project_id || project_deleted)>
            , experiment_projects AS (
                SELECT
                    ei.experiment_id,
                    groupUniqArray(t.project_id) AS project_ids
                FROM experiment_items_final ei
                LEFT JOIN (
                    SELECT
                        id,
                        project_id
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    <if(has_target_projects)>
                    AND project_id IN :target_project_ids
                    <else>
                    AND id IN (SELECT trace_id FROM experiment_items_final)
                    <endif>
                ) t ON ei.trace_id = t.id
                GROUP BY ei.experiment_id
            )
            <endif>
            SELECT count(e.id) as count
            FROM experiments_initial e
            <if(project_id || project_deleted)>
            LEFT JOIN experiment_projects ep ON e.id = ep.experiment_id
            <endif>
            WHERE 1=1
            <if(feedback_scores_filters)>
            AND id in (
                SELECT DISTINCT experiment_id FROM experiment_items_final
                WHERE trace_id IN (
                    SELECT
                    entity_id AS trace_id
                    FROM (
                        SELECT *
                        FROM feedback_scores_final
                        ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                        LIMIT 1 BY entity_id, name
                    )
                    GROUP BY entity_id
                    HAVING <feedback_scores_filters>
                )
            )
            <endif>
            <if(feedback_scores_empty_filters)>
            AND id NOT IN (
               SELECT DISTINCT experiment_id FROM experiment_items_final
               WHERE trace_id IN (SELECT entity_id FROM fsc)
            )
            <endif>
            <if(experiment_scores_filters)>
            AND id IN (
                SELECT experiment_id
                FROM experiment_scores_final
                GROUP BY experiment_id
                HAVING <experiment_scores_filters>
            )
            <endif>
            <if(experiment_scores_empty_filters)>
            AND e.id NOT IN (SELECT experiment_id FROM esc)
            <endif>
            <if(project_id)>
            AND has(ep.project_ids, :project_id)
            <endif>
            <if(project_deleted)>
            AND (has(ep.project_ids, '') OR empty(ep.project_ids))
            <endif>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String FIND_GROUPS = """
            WITH experiments_filtered AS (
                SELECT
                    id,
                    dataset_id,
                    metadata,
                    tags,
                    arrayConcat([prompt_id], mapKeys(prompt_versions)) AS prompt_ids,
                    created_at
                FROM experiments final
                WHERE workspace_id = :workspace_id
                <if(types)> AND type IN :types <endif>
                <if(name)> AND ilike(name, CONCAT('%', :name, '%')) <endif>
                <if(filters)> AND <filters> <endif>
            ), experiment_items_final AS (
                SELECT
                    id, experiment_id, trace_id
                FROM experiment_items final
                WHERE workspace_id = :workspace_id
                AND experiment_id IN (SELECT id FROM experiments_filtered)
            ), experiments_with_projects AS (
                SELECT
                    ef.id,
                    ef.dataset_id,
                    ef.metadata,
                    ef.tags,
                    ef.prompt_ids,
                    ef.created_at,
                    ep.project_ids,
                    if(empty(ep.project_ids), '', ep.project_ids[1]) as project_id
                FROM experiments_filtered ef
                LEFT JOIN (
                    SELECT
                        ei.experiment_id,
                        groupUniqArray(t.project_id) AS project_ids
                    FROM experiment_items_final ei
                    LEFT JOIN (
                        SELECT
                            id,
                            project_id
                        FROM traces
                        WHERE workspace_id = :workspace_id
                        <if(has_target_projects)>
                        AND project_id IN :target_project_ids
                        <else>
                        AND id IN (SELECT trace_id FROM experiment_items_final)
                        <endif>
                    ) t ON ei.trace_id = t.id
                    GROUP BY ei.experiment_id
                ) ep ON ef.id = ep.experiment_id
            )
            SELECT <groupSelects>, max(created_at) AS last_created_experiment_at
            FROM experiments_with_projects
            WHERE 1=1
            <if(project_id)>
            AND has(project_ids, :project_id)
            <endif>
            <if(project_deleted)>
            AND (has(project_ids, '') OR empty(project_ids))
            <endif>
            GROUP BY <groupBy>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * Query to get target project IDs for experiment queries.
     * Used to optimize FIND, FIND_COUNT, FIND_GROUPS, and FIND_GROUPS_AGGREGATIONS queries
     * by pre-computing project IDs, reducing traces, spans, and feedback_scores table scans.
     */
    private static final String SELECT_TARGET_PROJECTS = """
            WITH experiments_final AS (
                SELECT
                    id, arrayConcat([prompt_id], mapKeys(prompt_versions)) AS prompt_ids
                FROM experiments final
                WHERE workspace_id = :workspace_id
                <if(dataset_id)> AND dataset_id = :dataset_id <endif>
                <if(optimization_id)> AND optimization_id = :optimization_id <endif>
                <if(types)> AND type IN :types <endif>
                <if(name)> AND ilike(name, CONCAT('%', :name, '%')) <endif>
                <if(dataset_ids)> AND dataset_id IN :dataset_ids <endif>
                <if(experiment_ids)> AND id IN :experiment_ids <endif>
                <if(prompt_ids)>AND hasAny(prompt_ids, :prompt_ids)<endif>
                <if(filters)> AND <filters> <endif>
            ), experiment_items_trace_scope AS (
                SELECT DISTINCT ei.trace_id
                FROM experiment_items ei
                WHERE ei.workspace_id = :workspace_id
                AND ei.experiment_id IN (SELECT id FROM experiments_final)
            )
            SELECT DISTINCT project_id
            FROM traces final
            WHERE workspace_id = :workspace_id
            AND id IN (SELECT trace_id FROM experiment_items_trace_scope)
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String FIND_GROUPS_AGGREGATIONS = """
            WITH experiments_final AS (
                SELECT
                    id, dataset_id, metadata, tags, experiment_scores, arrayConcat([prompt_id], mapKeys(prompt_versions)) AS prompt_ids
                FROM experiments final
                WHERE workspace_id = :workspace_id
                <if(types)> AND type IN :types <endif>
                <if(name)> AND ilike(name, CONCAT('%', :name, '%')) <endif>
                <if(filters)> AND <filters> <endif>
            ), experiment_items_final AS (
                SELECT DISTINCT
                    id, experiment_id, trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id IN (SELECT id FROM experiments_final)
            ), experiment_durations AS (
                SELECT
                    experiment_id,
                    groupUniqArray(project_id) AS project_ids,
                    mapFromArrays(
                        ['p50', 'p90', 'p99'],
                        arrayMap(
                          v -> toDecimal64(
                                 greatest(
                                   least(if(isFinite(v), v, 0),  999999999.999999999),
                                   -999999999.999999999
                                 ),
                                 9
                               ),
                          quantiles(0.5, 0.9, 0.99)(duration)
                        )
                    ) AS duration_values,
                    count(DISTINCT ei.trace_id) as trace_count,
                    sum(total_estimated_cost) as total_estimated_cost_sum,
                    avg(total_estimated_cost) as total_estimated_cost_avg
                FROM experiment_items_final ei
                LEFT JOIN (
                    SELECT
                        id,
                        duration,
                        project_id
                    FROM traces final
                    WHERE workspace_id = :workspace_id
                    <if(has_target_projects)>AND project_id IN :target_project_ids
                    <else>
                    AND id IN (SELECT trace_id FROM experiment_items_final)
                    <endif>
                ) AS t ON ei.trace_id = t.id
                LEFT JOIN (
                    SELECT
                        trace_id,
                        sum(total_estimated_cost) as total_estimated_cost
                    FROM spans final
                    WHERE workspace_id = :workspace_id
                    <if(has_target_projects)>AND project_id IN :target_project_ids
                    <else>
                    AND trace_id IN (SELECT trace_id FROM experiment_items_final)
                    <endif>
                    GROUP BY workspace_id, project_id, trace_id
                ) AS s ON t.id = s.trace_id
                GROUP BY experiment_id
            ), feedback_scores_combined_raw AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       feedback_scores.last_updated_by AS author
                FROM feedback_scores FINAL
                WHERE entity_type = 'trace'
                AND workspace_id = :workspace_id
                <if(has_target_projects)>AND project_id IN :target_project_ids
                <else>
                AND entity_id IN (SELECT trace_id FROM experiment_items_final)
                <endif>
                UNION ALL
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    value,
                    last_updated_at,
                    author
                FROM authored_feedback_scores FINAL
                WHERE entity_type = 'trace'
                AND workspace_id = :workspace_id
                <if(has_target_projects)>AND project_id IN :target_project_ids
                <else>
                AND entity_id IN (SELECT trace_id FROM experiment_items_final)
                <endif>
            ), feedback_scores_with_ranking AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author,
                       ROW_NUMBER() OVER (
                           PARTITION BY workspace_id, project_id, entity_id, name, author
                           ORDER BY last_updated_at DESC
                       ) as rn
                FROM feedback_scores_combined_raw
            ), feedback_scores_combined AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author
                FROM feedback_scores_with_ranking
                WHERE rn = 1
            ), feedback_scores_final AS (
                SELECT
                    fsc.workspace_id,
                    fsc.project_id,
                    fsc.entity_id,
                    fsc.name,
                    if(count() = 1, any(fsc.value), toDecimal64(avg(fsc.value), 9)) AS value
                FROM feedback_scores_combined fsc
                GROUP BY fsc.workspace_id, fsc.project_id, fsc.entity_id, fsc.name
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
                    FROM experiment_items_final as et
                    INNER JOIN (
                        SELECT DISTINCT id FROM traces
                        WHERE workspace_id = :workspace_id
                        <if(has_target_projects)>AND project_id IN :target_project_ids
                        <else>
                        AND id IN (SELECT trace_id FROM experiment_items_final)
                        <endif>
                    ) AS t ON et.trace_id = t.id
                    LEFT JOIN feedback_scores_final fs ON fs.entity_id = et.trace_id
                    GROUP BY et.experiment_id, fs.name
                    HAVING length(fs.name) > 0
                ) as fs_avg
                GROUP BY experiment_id
            ),
            experiment_scores_agg AS (
                SELECT
                    experiment_id,
                    mapFromArrays(
                        groupArray(name),
                        groupArray(value)
                    ) AS experiment_scores
                FROM (
                    SELECT
                        e.id AS experiment_id,
                        JSON_VALUE(score, '$.name') AS name,
                        CAST(JSON_VALUE(score, '$.value') AS Float64) AS value
                    FROM experiments_final AS e
                    ARRAY JOIN JSONExtractArrayRaw(e.experiment_scores) AS score
                    WHERE length(e.experiment_scores) > 2
                      AND length(JSON_VALUE(score, '$.name')) > 0
                ) AS es
                GROUP BY experiment_id
            ), experiments_full AS (
                SELECT
                    e.id as id,
                    e.dataset_id AS dataset_id,
                    e.metadata AS metadata,
                    e.tags AS tags,
                    fs.feedback_scores as feedback_scores,
                    es.experiment_scores as experiment_scores,
                    ed.trace_count as trace_count,
                    ed.duration_values AS duration,
                    ed.total_estimated_cost_sum as total_estimated_cost,
                    ed.total_estimated_cost_avg as total_estimated_cost_avg,
                    ed.project_ids as project_ids,
                    if(empty(ed.project_ids), '', ed.project_ids[1]) as project_id
                FROM experiments_final AS e
                LEFT JOIN experiment_durations AS ed ON e.id = ed.experiment_id
                LEFT JOIN feedback_scores_agg AS fs ON e.id = fs.experiment_id
                LEFT JOIN experiment_scores_agg AS es ON e.id = es.experiment_id
            )
            SELECT
                count(DISTINCT id) as experiment_count,
                sum(trace_count) as trace_count,
                sum(total_estimated_cost) as total_estimated_cost,
                avg(total_estimated_cost_avg) as total_estimated_cost_avg,
                avgMap(feedback_scores) as feedback_scores,
                avgMap(experiment_scores) as experiment_scores,
                avgMap(duration) as duration,
                <groupSelects>
            FROM experiments_full
            WHERE 1=1
            <if(project_id)>
            AND has(project_ids, :project_id)
            <endif>
            <if(project_deleted)>
            AND (has(project_ids, '') OR empty(project_ids))
            <endif>
            GROUP BY <groupBy>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String FIND_BY_NAME = """
            SELECT
                *,
                null AS feedback_scores,
                null AS trace_count,
                null AS duration,
                null AS total_estimated_cost,
                null AS total_estimated_cost_avg,
                null AS usage,
                null AS comments_array_agg
            FROM experiments
            WHERE workspace_id = :workspace_id
            AND ilike(name, CONCAT('%', :name, '%'))
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1 BY id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String FIND_EXPERIMENT_AND_WORKSPACE_BY_EXPERIMENT_IDS = """
            SELECT
                DISTINCT id, workspace_id
            FROM experiments
            WHERE id in :experiment_ids
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String DELETE_BY_IDS = """
            DELETE FROM experiments
            WHERE id IN :ids
            AND workspace_id = :workspace_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;
    private static final String FIND_MOST_RECENT_CREATED_EXPERIMENT_BY_DATASET_IDS = """
            SELECT
                dataset_id,
                max(created_at) as created_at
            FROM experiments
            WHERE dataset_id IN :dataset_ids
            AND workspace_id = :workspace_id
            GROUP BY dataset_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String FIND_EXPERIMENT_DATASET_ID_EXPERIMENT_IDS = """
            SELECT
                distinct dataset_id, type
            FROM experiments
            WHERE workspace_id = :workspace_id
            <if(experiment_ids)> AND id IN :experiment_ids <endif>
            <if(prompt_ids)>AND (prompt_id IN :prompt_ids OR hasAny(mapKeys(prompt_versions), :prompt_ids))<endif>
            SETTINGS log_comment = '<log_comment>'
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
                WHERE name IN :excluded_names
            )
            GROUP BY workspace_id, created_by
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String UPDATE = """
            INSERT INTO experiments (
                id,
                dataset_id,
                name,
                workspace_id,
                metadata,
                tags,
                created_by,
                last_updated_by,
                prompt_version_id,
                prompt_id,
                prompt_versions,
                type,
                optimization_id,
                status,
                experiment_scores,
                created_at,
                last_updated_at
            )
            SELECT
                id,
                dataset_id,
                <if(name)> :name <else> name <endif> as name,
                workspace_id,
                <if(metadata)> :metadata <else> metadata <endif> as metadata,
                <if(tags)><if(merge_tags)> arrayDistinct(arrayConcat(tags, :tags)) <else> :tags <endif> <else> tags <endif> as tags,
                created_by,
                :user_name as last_updated_by,
                prompt_version_id,
                prompt_id,
                prompt_versions,
                <if(type)> :type <else> type <endif> as type,
                optimization_id,
                <if(status)> :status <else> status <endif> as status,
                <if(experiment_scores)> :experiment_scores <else> experiment_scores <endif> as experiment_scores,
                created_at,
                now64(9) as last_updated_at
            FROM experiments
            WHERE id IN (:ids)
            AND workspace_id = :workspace_id
            ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
            LIMIT 1 BY id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull ExperimentSortingFactory sortingFactory;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;
    private final @NonNull GroupingQueryBuilder groupingQueryBuilder;

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
                .bind("optimization_id", Optional.ofNullable(experiment.optimizationId())
                        .map(UUID::toString)
                        .orElse(""))
                .bind("status", Optional.ofNullable(experiment.status()).orElse(ExperimentStatus.COMPLETED).getValue())
                .bind("experiment_scores", Optional.ofNullable(experiment.experimentScores())
                        .filter(scores -> !scores.isEmpty())
                        .map(JsonUtils::writeValueAsString)
                        .orElse(""))
                .bind("dataset_version_id", Optional.ofNullable(experiment.datasetVersionId())
                        .map(UUID::toString)
                        .orElse(""));

        if (CollectionUtils.isNotEmpty(experiment.tags())) {
            statement.bind("tags", experiment.tags().toArray(String[]::new));
        } else {
            statement.bind("tags", new String[]{});
        }

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
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> makeFluxContextAware((userName, workspaceId) -> {
                    var template = getSTWithLogComment(FIND, "get_experiment_by_id", workspaceId, "");
                    template.add("id", id.toString());
                    template.add("limit", limit);
                    return Flux.from(get(template.render(), connection,
                            statement -> statement.bind("id", id).bind("limit", limit).bind("workspace_id",
                                    workspaceId)));
                }))
                .flatMap(this::mapToDto)
                .singleOrEmpty();
    }

    @WithSpan
    Flux<Experiment> getByIds(@NonNull Set<UUID> ids) {
        log.info("Getting experiment by ids '{}'", ids);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> makeFluxContextAware((userName, workspaceId) -> {
                    var template = getSTWithLogComment(FIND, "get_experiments_by_ids", workspaceId, ids.size());
                    template.add("ids_list", ids);
                    return Flux.from(get(template.render(), connection,
                            statement -> statement.bind("ids_list", ids.toArray(UUID[]::new)).bind("workspace_id",
                                    workspaceId)));
                }))
                .flatMap(this::mapToDto);
    }

    @WithSpan
    Flux<Experiment> get(@NonNull ExperimentStreamRequest request) {
        log.info("Getting experiment by '{}'", request);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> makeFluxContextAware((userName, workspaceId) -> {
                    var template = getSTWithLogComment(FIND, "get_experiments_stream", workspaceId, "");
                    template.add("name", request.name());
                    if (request.lastRetrievedId() != null) {
                        template.add("lastRetrievedId", request.lastRetrievedId());
                    }
                    template.add("limit", request.limit());
                    return Flux.from(get(template.render(), connection,
                            statement -> {
                                statement.bind("name", request.name());
                                if (request.lastRetrievedId() != null) {
                                    statement = statement.bind("lastRetrievedId", request.lastRetrievedId());
                                }
                                return statement.bind("limit", request.limit()).bind("workspace_id", workspaceId);
                            }));
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
                    .projectId(RowUtils.getOptionalValue(row, "project_id", UUID.class))
                    .name(row.get("name", String.class))
                    .metadata(getJsonNodeOrDefault(row.get("metadata", String.class)))
                    .tags(Optional.ofNullable(row.get("tags", String[].class))
                            .map(tags -> Arrays.stream(tags).collect(toSet()))
                            .filter(CollectionUtils::isNotEmpty)
                            .orElse(null))
                    .createdAt(row.get("created_at", Instant.class))
                    .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                    .createdBy(row.get("created_by", String.class))
                    .lastUpdatedBy(row.get("last_updated_by", String.class))
                    .feedbackScores(getFeedbackScores(row, "feedback_scores"))
                    .comments(getComments(row.get("comments_array_agg", List[].class)))
                    .traceCount(row.get("trace_count", Long.class))
                    .duration(getDuration(row))
                    .totalEstimatedCost(getCostValue(row, "total_estimated_cost"))
                    .totalEstimatedCostAvg(getCostValue(row, "total_estimated_cost_avg"))
                    .usage(row.get("usage", Map.class))
                    .promptVersion(promptVersions.stream().findFirst().orElse(null))
                    .promptVersions(promptVersions.isEmpty() ? null : promptVersions)
                    .optimizationId(Optional.ofNullable(row.get("optimization_id", String.class))
                            .filter(str -> !str.isBlank())
                            .map(UUID::fromString)
                            .orElse(null))
                    .type(ExperimentType.fromString(row.get("type", String.class)))
                    .status(ExperimentStatus.fromString(row.get("status", String.class)))
                    .experimentScores(getExperimentScores(row))
                    .datasetVersionId(Optional.ofNullable(row.get("dataset_version_id", String.class))
                            .filter(str -> !str.isBlank())
                            .map(UUID::fromString)
                            .orElse(null))
                    .build();
        });
    }

    private static BigDecimal getCostValue(Row row, String fieldName) {
        return Optional.ofNullable(row.get(fieldName, BigDecimal.class))
                .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                .orElse(null);
    }

    private static PercentageValues getDuration(Row row) {
        return Optional.ofNullable(row.get("duration", Map.class))
                .map(map -> (Map<String, ? extends Number>) map)
                .map(durations -> new PercentageValues(
                        convertToBigDecimal(durations.get("p50")),
                        convertToBigDecimal(durations.get("p90")),
                        convertToBigDecimal(durations.get("p99"))))
                .orElse(null);
    }

    private static BigDecimal convertToBigDecimal(Number value) {
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        } else if (value instanceof Double) {
            return BigDecimal.valueOf((Double) value);
        } else {
            return BigDecimal.ZERO;
        }
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

    public static List<FeedbackScoreAverage> getFeedbackScores(Row row, String columnName) {
        return getScoresAggregation(row, columnName);
    }

    private static List<FeedbackScoreAverage> getScoresAggregation(Row row, String columnName) {
        List<FeedbackScoreAverage> scoresAvg = Optional
                .ofNullable(row.get(columnName, Map.class))
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

        return scoresAvg.isEmpty() ? null : scoresAvg;
    }

    public static List<ExperimentScore> getExperimentScores(Row row) {
        String experimentScoresJson = row.get("experiment_scores", String.class);
        if (StringUtils.isBlank(experimentScoresJson)) {
            return null;
        }
        try {
            List<ExperimentScore> scores = JsonUtils.readValue(experimentScoresJson,
                    ExperimentScore.LIST_TYPE_REFERENCE);
            return CollectionUtils.isEmpty(scores) ? null : scores;
        } catch (Exception e) {
            log.warn("Failed to deserialize experiment_scores from JSON: {}", experimentScoresJson, e);
            return null;
        }
    }

    @WithSpan
    Mono<ExperimentPage> find(
            int page, int size, @NonNull ExperimentSearchCriteria experimentSearchCriteria) {
        return Mono.deferContextual(ctx -> {
            // First, get the target project IDs to reduce traces, spans, and feedback_scores table scans
            return getTargetProjectIdsForExperiments(TargetProjectsCriteria.from(experimentSearchCriteria))
                    .flatMap(targetProjectIds -> countTotal(experimentSearchCriteria, targetProjectIds)
                            .flatMap(total -> find(page, size, experimentSearchCriteria, total, targetProjectIds)));
        });
    }

    private Mono<ExperimentPage> find(
            int page, int size, ExperimentSearchCriteria experimentSearchCriteria, Long total,
            Set<UUID> targetProjectIds) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> find(page, size, experimentSearchCriteria, connection, targetProjectIds))
                .flatMap(this::mapToDto)
                .collectList()
                .map(experiments -> new ExperimentPage(page, experiments.size(), total, experiments,
                        sortingFactory.getSortableFields()));
    }

    private Publisher<? extends Result> find(
            int page, int size, ExperimentSearchCriteria experimentSearchCriteria, Connection connection,
            Set<UUID> targetProjectIds) {
        log.info("Finding experiments by '{}', page '{}', size '{}'", experimentSearchCriteria, page, size);

        return makeFluxContextAware((userName, workspaceId) -> {
            var sorting = sortingQueryBuilder.toOrderBySql(experimentSearchCriteria.sortingFields());

            var hasDynamicKeys = sortingQueryBuilder.hasDynamicKeys(experimentSearchCriteria.sortingFields());

            int offset = (page - 1) * size;

            var template = newFindTemplate(FIND, experimentSearchCriteria, "find_experiments", workspaceId);

            // Add target project IDs flag to template (from separate query to reduce table scans)
            if (CollectionUtils.isNotEmpty(targetProjectIds)) {
                template.add("has_target_projects", true);
            }

            template.add("sort_fields", sorting);
            template.add("limit", size);
            template.add("offset", offset);

            var statement = connection.createStatement(template.render())
                    .bind("limit", size)
                    .bind("offset", offset)
                    .bind("workspace_id", workspaceId);

            // Bind target project IDs (from separate query to reduce table scans)
            if (CollectionUtils.isNotEmpty(targetProjectIds)) {
                statement.bind("target_project_ids", targetProjectIds.toArray(UUID[]::new));
            }

            if (hasDynamicKeys) {
                statement = sortingQueryBuilder.bindDynamicKeys(statement, experimentSearchCriteria.sortingFields());
            }

            bindSearchCriteria(statement, experimentSearchCriteria, false);
            return Flux.from(statement.execute());
        });
    }

    private Mono<Long> countTotal(ExperimentSearchCriteria experimentSearchCriteria, Set<UUID> targetProjectIds) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> countTotal(experimentSearchCriteria, connection, targetProjectIds))
                .flatMap(result -> result.map((row, rowMetadata) -> row.get("count", Long.class)))
                .reduce(0L, Long::sum);
    }

    private Publisher<? extends Result> countTotal(
            ExperimentSearchCriteria experimentSearchCriteria, Connection connection, Set<UUID> targetProjectIds) {
        log.info("Counting experiments by '{}'", experimentSearchCriteria);
        return makeFluxContextAware((userName, workspaceId) -> {
            var template = newFindTemplate(FIND_COUNT, experimentSearchCriteria, "count_experiments", workspaceId);

            // Add target project IDs flag to template (from separate query to reduce table scans)
            if (CollectionUtils.isNotEmpty(targetProjectIds)) {
                template.add("has_target_projects", true);
            }

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId);

            // Bind target project IDs (from separate query to reduce table scans)
            if (CollectionUtils.isNotEmpty(targetProjectIds)) {
                statement.bind("target_project_ids", targetProjectIds.toArray(UUID[]::new));
            }

            bindSearchCriteria(statement, experimentSearchCriteria, true);
            return Flux.from(statement.execute());
        });
    }

    private ST newFindTemplate(String query, ExperimentSearchCriteria criteria, String queryName, String workspaceId) {
        var template = getSTWithLogComment(query, queryName, workspaceId, "");
        Optional.ofNullable(criteria.datasetId())
                .ifPresent(datasetId -> template.add("dataset_id", datasetId));
        Optional.ofNullable(criteria.name())
                .ifPresent(name -> template.add("name", name));
        Optional.ofNullable(criteria.datasetIds())
                .ifPresent(datasetIds -> template.add("dataset_ids", datasetIds));
        Optional.ofNullable(criteria.promptId())
                .ifPresent(promptId -> template.add("prompt_ids", promptId));
        Optional.ofNullable(criteria.projectId())
                .ifPresent(projectId -> template.add("project_id", projectId));
        if (criteria.projectDeleted()) {
            template.add("project_deleted", true);
        }
        Optional.ofNullable(criteria.optimizationId())
                .ifPresent(optimizationId -> template.add("optimization_id", optimizationId));
        Optional.ofNullable(criteria.types())
                .filter(CollectionUtils::isNotEmpty)
                .ifPresent(types -> template.add("types", types));
        Optional.ofNullable(criteria.experimentIds())
                .filter(CollectionUtils::isNotEmpty)
                .ifPresent(experimentIds -> template.add("experiment_ids", experimentIds));
        Optional.ofNullable(criteria.filters())
                .flatMap(filters -> filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.EXPERIMENT))
                .ifPresent(experimentFilters -> template.add("filters", experimentFilters));
        Optional.ofNullable(criteria.filters())
                .flatMap(filters -> filterQueryBuilder.toAnalyticsDbFilters(filters,
                        FilterStrategy.FEEDBACK_SCORES))
                .ifPresent(feedbackScoresFilters -> template.add("feedback_scores_filters", feedbackScoresFilters));
        Optional.ofNullable(criteria.filters())
                .flatMap(filters -> filterQueryBuilder.toAnalyticsDbFilters(filters,
                        FilterStrategy.FEEDBACK_SCORES_IS_EMPTY))
                .ifPresent(feedbackScoresEmptyFilters -> template.add("feedback_scores_empty_filters",
                        feedbackScoresEmptyFilters));
        Optional.ofNullable(criteria.filters())
                .flatMap(filters -> filterQueryBuilder.toAnalyticsDbFilters(filters,
                        FilterStrategy.EXPERIMENT_SCORES))
                .ifPresent(
                        experimentScoresFilters -> template.add("experiment_scores_filters", experimentScoresFilters));
        Optional.ofNullable(criteria.filters())
                .flatMap(filters -> filterQueryBuilder.toAnalyticsDbFilters(filters,
                        FilterStrategy.EXPERIMENT_SCORES_IS_EMPTY))
                .ifPresent(experimentScoresEmptyFilters -> template.add("experiment_scores_empty_filters",
                        experimentScoresEmptyFilters));
        return template;
    }

    private void bindSearchCriteria(Statement statement, ExperimentSearchCriteria criteria, boolean isCount) {
        ExperimentSearchCriteriaBinder.bindSearchCriteria(
                statement,
                criteria,
                filterQueryBuilder,
                List.of(
                        FilterStrategy.EXPERIMENT,
                        FilterStrategy.FEEDBACK_SCORES,
                        FilterStrategy.FEEDBACK_SCORES_IS_EMPTY,
                        FilterStrategy.EXPERIMENT_SCORES,
                        FilterStrategy.EXPERIMENT_SCORES_IS_EMPTY),
                !isCount // Bind entity_type when not a count query
        );
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

        log.info("Deleting experiments by ids, size '{}'", ids.size());

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> delete(ids, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(Long::sum)
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("Deleted experiments by ids, size '{}'", ids.size());
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
                .flatMapMany(connection -> makeFluxContextAware((userName, workspaceId) -> {
                    var template = getSTWithLogComment(FIND_EXPERIMENT_DATASET_ID_EXPERIMENT_IDS,
                            "get_experiments_dataset_info", workspaceId, ids.size());
                    template.add("experiment_ids", ids);
                    var statement = connection.createStatement(template.render())
                            .bind("experiment_ids", ids.toArray(UUID[]::new))
                            .bind("workspace_id", workspaceId);
                    return Flux.from(statement.execute());
                }))
                .flatMap(this::mapDatasetInfo)
                .collectList();
    }

    @WithSpan
    public Mono<List<DatasetEventInfoHolder>> findAllDatasetIds(@NonNull DatasetCriteria criteria) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> makeFluxContextAware((userName, workspaceId) -> {
                    var template = getSTWithLogComment(FIND_EXPERIMENT_DATASET_ID_EXPERIMENT_IDS,
                            "find_all_dataset_ids", workspaceId, "");

                    bindFindAllDatasetIdsTemplateParams(criteria, template);

                    var statement = connection.createStatement(template.render())
                            .bind("workspace_id", workspaceId);

                    bindFindAllDatasetIdsParams(criteria, statement);

                    return Flux.from(statement.execute());
                }))
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
                        .bind("excluded_names", DemoData.EXPERIMENTS)
                        .execute())
                .flatMap(result -> result.map((row, rowMetadata) -> row.get("experiment_count", Long.class)))
                .reduce(0L, Long::sum);
    }

    @WithSpan
    public Flux<ExperimentGroupItem> findGroups(@NonNull ExperimentGroupCriteria criteria) {
        log.info("Finding experiment groups by criteria '{}'", criteria);

        return executeQueryWithTargetProjects(
                FIND_GROUPS,
                "find_experiment_groups",
                criteria,
                this::mapExperimentGroupItem);
    }

    @WithSpan
    public Flux<ExperimentGroupAggregationItem> findGroupsAggregations(@NonNull ExperimentGroupCriteria criteria) {
        log.info("Finding experiment groups aggregations by criteria '{}'", criteria);

        return executeQueryWithTargetProjects(
                FIND_GROUPS_AGGREGATIONS,
                "find_experiment_groups_aggregations",
                criteria,
                this::mapExperimentGroupAggregationItem);
    }

    /**
     * Helper method to execute group queries with target project optimization.
     * Handles the common pattern of:
     * 1. Fetching target project IDs to reduce table scans
     * 2. Building the query template with has_target_projects flag
     * 3. Binding criteria and target project IDs
     * 4. Executing the query with context-aware workspace binding
     *
     * @param queryTemplate The SQL query template (FIND_GROUPS or FIND_GROUPS_AGGREGATIONS)
     * @param queryName The query name for logging
     * @param criteria The experiment group criteria
     * @param resultMapper Function to map Result to the desired type
     * @return Flux of mapped results
     */
    private <T> Flux<T> executeQueryWithTargetProjects(
            String queryTemplate,
            String queryName,
            ExperimentGroupCriteria criteria,
            BiFunction<Result, Integer, Publisher<T>> resultMapper) {

        return Flux.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return getTargetProjectIdsForExperiments(TargetProjectsCriteria.from(criteria))
                    .flatMapMany(targetProjectIds -> {
                        boolean hasTargetProjects = CollectionUtils.isNotEmpty(targetProjectIds);
                        log.debug("{}: hasTargetProjects='{}', targetProjectIds='{}', criteria='{}'",
                                queryName, hasTargetProjects, targetProjectIds, criteria);

                        return Mono.from(connectionFactory.create())
                                .flatMapMany(connection -> {
                                    var template = newGroupTemplate(queryTemplate, criteria, queryName, workspaceId);

                                    // Add target project IDs flag to template (from separate query to reduce table scans)
                                    if (hasTargetProjects) {
                                        template.add("has_target_projects", true);
                                    }

                                    var statement = connection.createStatement(template.render());

                                    bindGroupCriteria(statement, criteria);

                                    // Bind target project IDs (from separate query to reduce table scans)
                                    if (hasTargetProjects) {
                                        statement.bind("target_project_ids", targetProjectIds.toArray(UUID[]::new));
                                    }

                                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                                })
                                .flatMap(result -> resultMapper.apply(result, criteria.groups().size()));
                    });
        });
    }

    private ST newGroupTemplate(String query, ExperimentGroupCriteria criteria, String queryName, String workspaceId) {
        var template = getSTWithLogComment(query, queryName, workspaceId, "");

        Optional.ofNullable(criteria.name())
                .ifPresent(name -> template.add("name", name));
        Optional.ofNullable(criteria.types())
                .filter(CollectionUtils::isNotEmpty)
                .ifPresent(types -> template.add("types", types));
        Optional.ofNullable(criteria.filters())
                .flatMap(filters -> filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.EXPERIMENT))
                .ifPresent(experimentFilters -> template.add("filters", experimentFilters));
        Optional.ofNullable(criteria.projectId())
                .ifPresent(projectId -> template.add("project_id", projectId));
        Optional.ofNullable(criteria.projectDeleted())
                .ifPresent(projectDeleted -> template.add("project_deleted", projectDeleted));

        groupingQueryBuilder.addGroupingTemplateParams(criteria.groups(), template);

        return template;
    }

    private void bindGroupCriteria(Statement statement, ExperimentGroupCriteria criteria) {
        Optional.ofNullable(criteria.name())
                .ifPresent(name -> statement.bind("name", name));
        Optional.ofNullable(criteria.types())
                .filter(CollectionUtils::isNotEmpty)
                .ifPresent(types -> statement.bind("types", types));
        Optional.ofNullable(criteria.filters())
                .ifPresent(filters -> {
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.EXPERIMENT);
                });
        Optional.ofNullable(criteria.projectId())
                .ifPresent(projectId -> statement.bind("project_id", projectId));
    }

    /**
     * Get target project IDs from traces for the given experiments.
     * This is executed as a separate query to reduce traces, spans, and feedback_scores table scans in the main query.
     */
    private Mono<Set<UUID>> getTargetProjectIdsForExperiments(TargetProjectsCriteria criteria) {
        // Skip optimization when shouldSkipOptimization() returns true (e.g., projectDeleted=true)
        if (criteria.shouldSkipOptimization()) {
            log.info("Skipping target project IDs optimization due to projectDeleted='{}', criteria='{}'",
                    criteria.projectDeleted(), criteria);
            return Mono.just(Set.of());
        }

        return Mono.from(connectionFactory.create())
                .flatMap(connection -> {
                    var template = TemplateUtils.newST(SELECT_TARGET_PROJECTS);

                    Optional.ofNullable(criteria.datasetId())
                            .ifPresent(datasetId -> template.add("dataset_id", datasetId));
                    Optional.ofNullable(criteria.name())
                            .ifPresent(name -> template.add("name", name));
                    Optional.ofNullable(criteria.datasetIds())
                            .filter(CollectionUtils::isNotEmpty)
                            .ifPresent(datasetIds -> template.add("dataset_ids", datasetIds));
                    Optional.ofNullable(criteria.promptId())
                            .ifPresent(promptId -> template.add("prompt_ids", promptId));
                    Optional.ofNullable(criteria.optimizationId())
                            .ifPresent(optimizationId -> template.add("optimization_id", optimizationId));
                    Optional.ofNullable(criteria.types())
                            .filter(CollectionUtils::isNotEmpty)
                            .ifPresent(types -> template.add("types", types));
                    Optional.ofNullable(criteria.experimentIds())
                            .filter(CollectionUtils::isNotEmpty)
                            .ifPresent(experimentIds -> template.add("experiment_ids", experimentIds));
                    Optional.ofNullable(criteria.filters())
                            .flatMap(filters -> filterQueryBuilder.toAnalyticsDbFilters(filters,
                                    FilterStrategy.EXPERIMENT))
                            .ifPresent(experimentFilters -> template.add("filters", experimentFilters));

                    template.add("log_comment", "get_target_project_ids_for_experiments");

                    String query = template.render();

                    var statement = connection.createStatement(query);

                    // Bind the same criteria as the main query
                    Optional.ofNullable(criteria.datasetId())
                            .ifPresent(datasetId -> statement.bind("dataset_id", datasetId));
                    Optional.ofNullable(criteria.name())
                            .ifPresent(name -> statement.bind("name", name));
                    Optional.ofNullable(criteria.datasetIds())
                            .filter(CollectionUtils::isNotEmpty)
                            .ifPresent(datasetIds -> statement.bind("dataset_ids", datasetIds.toArray(UUID[]::new)));
                    Optional.ofNullable(criteria.promptId())
                            .ifPresent(
                                    promptId -> statement.bind("prompt_ids", List.of(promptId).toArray(UUID[]::new)));
                    Optional.ofNullable(criteria.optimizationId())
                            .ifPresent(optimizationId -> statement.bind("optimization_id", optimizationId));
                    Optional.ofNullable(criteria.types())
                            .filter(CollectionUtils::isNotEmpty)
                            .ifPresent(types -> statement.bind("types", types));
                    Optional.ofNullable(criteria.experimentIds())
                            .filter(CollectionUtils::isNotEmpty)
                            .ifPresent(experimentIds -> statement.bind("experiment_ids",
                                    experimentIds.toArray(UUID[]::new)));
                    Optional.ofNullable(criteria.filters())
                            .ifPresent(filters -> {
                                filterQueryBuilder.bind(statement, filters, FilterStrategy.EXPERIMENT);
                            });

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                            .flatMap(result -> result.map((row, metadata) -> {
                                var projectId = row.get("project_id", String.class);
                                return projectId != null && !projectId.isEmpty() ? UUID.fromString(projectId) : null;
                            }))
                            .filter(Objects::nonNull)
                            .collect(Collectors.toSet())
                            .doOnNext(projectIds -> log.info(
                                    "Target project IDs query returned '{}' project IDs: '{}', criteria='{}'",
                                    projectIds.size(), projectIds, criteria));
                });
    }

    private Publisher<ExperimentGroupItem> mapExperimentGroupItem(Result result, int groupsCount) {
        return result.map((row, rowMetadata) -> {

            var groupValues = IntStream.range(0, groupsCount)
                    .mapToObj(i -> "group_" + i)
                    .map(columnName -> row.get(columnName, String.class))
                    .toList();

            return ExperimentGroupItem.builder()
                    .groupValues(groupValues)
                    .lastCreatedExperimentAt(row.get("last_created_experiment_at", Instant.class))
                    .build();
        });
    }

    private Publisher<ExperimentGroupAggregationItem> mapExperimentGroupAggregationItem(Result result,
            int groupsCount) {
        return result.map((row, rowMetadata) -> {

            var groupValues = IntStream.range(0, groupsCount)
                    .mapToObj(i -> "group_" + i)
                    .map(columnName -> row.get(columnName, String.class))
                    .toList();

            var experimentCount = row.get("experiment_count", Long.class);
            var traceCount = row.get("trace_count", Long.class);
            var totalEstimatedCost = getCostValue(row, "total_estimated_cost");
            var totalEstimatedCostAvg = getCostValue(row, "total_estimated_cost_avg");
            var duration = getDuration(row);
            var feedbackScores = getFeedbackScores(row, "feedback_scores");
            var experimentScores = getFeedbackScores(row, "experiment_scores");

            return ExperimentGroupAggregationItem.builder()
                    .groupValues(groupValues)
                    .experimentCount(experimentCount)
                    .traceCount(traceCount)
                    .totalEstimatedCost(totalEstimatedCost)
                    .totalEstimatedCostAvg(totalEstimatedCostAvg)
                    .duration(duration)
                    .feedbackScores(feedbackScores)
                    .experimentScores(experimentScores)
                    .build();
        });
    }

    @WithSpan
    Mono<Void> update(@NonNull UUID id, @NonNull ExperimentUpdate experimentUpdate) {
        log.info("Updating experiment with id '{}'", id);
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> updateWithInsert(Set.of(id), experimentUpdate, false, "update_experiment",
                        connection))
                .then();
    }

    Mono<Void> update(@NonNull Set<UUID> ids, @NonNull ExperimentUpdate update, boolean mergeTags) {
        Preconditions.checkArgument(!ids.isEmpty(), "experiment IDs must not be empty");
        log.info("Updating batch of '{}' experiments", ids.size());
        return Mono.from(connectionFactory.create())
                .flatMapMany(
                        connection -> updateWithInsert(ids, update, mergeTags, "bulk_update_experiments", connection))
                .then();
    }

    private Publisher<? extends Result> updateWithInsert(@NonNull Set<UUID> ids,
            @NonNull ExperimentUpdate experimentUpdate,
            boolean mergeTags, String queryName, Connection connection) {

        return makeFluxContextAware((userName, workspaceId) -> {
            var template = buildUpdateTemplate(experimentUpdate, mergeTags, queryName, workspaceId);
            String sql = template.render();

            Statement statement = connection.createStatement(sql);
            bindUpdateParams(experimentUpdate, statement);
            statement.bind("ids", ids.toArray(UUID[]::new))
                    .bind("workspace_id", workspaceId)
                    .bind("user_name", userName);

            return Flux.from(statement.execute());
        });
    }

    private ST buildUpdateTemplate(ExperimentUpdate experimentUpdate, boolean mergeTags, String queryName,
            String workspaceId) {
        var template = getSTWithLogComment(UPDATE, queryName, workspaceId, "");

        if (StringUtils.isNotBlank(experimentUpdate.name())) {
            template.add("name", experimentUpdate.name());
        }

        if (experimentUpdate.metadata() != null) {
            template.add("metadata", experimentUpdate.metadata().toString());
        }

        // we are checking if tags are not null instead of using CollectionUtils.isNotEmpty
        // because an EMPTY set is a valid value here, and it is used to remove tags
        if (experimentUpdate.tags() != null) {
            template.add("tags", true);
            template.add("merge_tags", mergeTags);
        }

        if (experimentUpdate.type() != null) {
            template.add("type", experimentUpdate.type().getValue());
        }

        if (experimentUpdate.status() != null) {
            template.add("status", experimentUpdate.status().getValue());
        }

        if (experimentUpdate.experimentScores() != null) {
            template.add("experiment_scores", true);
        }

        return template;
    }

    private void bindUpdateParams(ExperimentUpdate experimentUpdate, Statement statement) {
        if (StringUtils.isNotBlank(experimentUpdate.name())) {
            statement.bind("name", experimentUpdate.name());
        }

        if (experimentUpdate.metadata() != null) {
            statement.bind("metadata", experimentUpdate.metadata().toString());
        }

        // we are checking if tags are not null instead of using CollectionUtils.isNotEmpty
        // because an EMPTY set is a valid value here, and it is used to remove tags
        if (experimentUpdate.tags() != null) {
            statement.bind("tags", experimentUpdate.tags().toArray(String[]::new));
        }

        if (experimentUpdate.type() != null) {
            statement.bind("type", experimentUpdate.type().getValue());
        }

        if (experimentUpdate.status() != null) {
            statement.bind("status", experimentUpdate.status().getValue());
        }

        if (experimentUpdate.experimentScores() != null) {
            String scoresJson = experimentUpdate.experimentScores().isEmpty()
                    ? ""
                    : JsonUtils.writeValueAsString(experimentUpdate.experimentScores());
            statement.bind("experiment_scores", scoresJson);
        }
    }

}
