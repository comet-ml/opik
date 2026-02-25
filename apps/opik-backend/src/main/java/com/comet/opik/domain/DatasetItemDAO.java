package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
import com.comet.opik.api.Column;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemUpdate;
import com.comet.opik.api.filter.DatasetItemFilter;
import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.api.sorting.SortingFactoryDatasets;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.DatasetItem.DatasetItemPage;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.infrastructure.DatabaseUtils.getSTWithLogComment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.Segment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.template.TemplateUtils.QueryItem;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;

@ImplementedBy(DatasetItemDAOImpl.class)
public interface DatasetItemDAO {
    Mono<Long> save(UUID datasetId, List<DatasetItem> batch);

    Mono<Long> delete(Set<UUID> ids, UUID datasetId, List<DatasetItemFilter> filters);

    Mono<DatasetItemPage> getItems(DatasetItemSearchCriteria datasetItemSearchCriteria, int page, int size);

    Mono<DatasetItem> get(UUID id);

    Flux<DatasetItem> getItems(UUID datasetId, int limit, UUID lastRetrievedId);

    Flux<DatasetItem> getItems(UUID datasetId, int limit, UUID lastRetrievedId,
            @NonNull List<DatasetItemFilter> filters);

    Mono<List<WorkspaceAndResourceId>> getDatasetItemWorkspace(Set<UUID> datasetItemIds);

    Flux<DatasetItemSummary> findDatasetItemSummaryByDatasetIds(Set<UUID> datasetIds);

    Mono<List<Column>> getOutputColumns(UUID datasetId, Set<UUID> experimentIds);

    Mono<com.comet.opik.api.ProjectStats> getExperimentItemsStats(UUID datasetId, Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters);

    Mono<Void> bulkUpdate(Set<UUID> ids, UUID datasetId, List<DatasetItemFilter> filters, DatasetItemUpdate update,
            boolean mergeTags);

    /**
     * Retrieves the column definitions (column names and their types) for the dataset items' data field.
     *
     * @param datasetId The ID of the dataset
     * @return A Mono containing a map of column names to their types
     */
    Mono<Map<String, List<String>>> getColumns(UUID datasetId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class DatasetItemDAOImpl implements DatasetItemDAO {

    private static final String INSERT_DATASET_ITEM = """
            INSERT INTO dataset_items (
                id,
                dataset_id,
                source,
                trace_id,
                span_id,
                data,
                tags,
                created_at,
                workspace_id,
                created_by,
                last_updated_by
            )
            SETTINGS log_comment = '<log_comment>'
            FORMAT Values
                <items:{item |
                    (
                         :id<item.index>,
                         :datasetId<item.index>,
                         :source<item.index>,
                         :traceId<item.index>,
                         :spanId<item.index>,
                         :data<item.index>,
                         :tags<item.index>,
                         now64(9),
                         :workspace_id,
                         :createdBy<item.index>,
                         :lastUpdatedBy<item.index>
                     )
                     <if(item.hasNext)>
                        ,
                     <endif>
                }>
            ;
            """;

    private static final String SELECT_DATASET_ITEM = """
            SELECT
                *,
                null AS experiment_items_array
            FROM dataset_items
            WHERE id = :id
            AND workspace_id = :workspace_id
            ORDER BY (workspace_id, dataset_id, source, trace_id, span_id, id) DESC, last_updated_at DESC
            LIMIT 1 BY id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_DATASET_ITEMS_STREAM = """
            SELECT
                *,
                null AS experiment_items_array
            FROM dataset_items
            WHERE dataset_id = :datasetId
            AND workspace_id = :workspace_id
            <if(lastRetrievedId)>AND id \\< :lastRetrievedId <endif>
            <if(dataset_item_filters)>AND (<dataset_item_filters>)<endif>
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1 BY id
            LIMIT :limit
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String DELETE_DATASET_ITEM = """
            DELETE FROM dataset_items
            WHERE workspace_id = :workspace_id
            <if(ids)> AND id IN :ids <endif>
            <if(dataset_id)> AND dataset_id = :dataset_id <endif>
            <if(dataset_item_filters)> AND (<dataset_item_filters>) <endif>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_DATASET_ITEMS = """
            SELECT
                id,
                dataset_id,
                <if(truncate)> mapApply((k, v) -> (k, substring(replaceRegexpAll(v, '<truncate>', '"[image]"'), 1, <truncationSize>)), data) as data <else> data <endif>,
                trace_id,
                span_id,
                source,
                tags,
                created_at,
                last_updated_at,
                created_by,
                last_updated_by,
                null AS experiment_items_array
            FROM dataset_items FINAL
            WHERE dataset_id = :datasetId
            AND workspace_id = :workspace_id
            <if(dataset_item_filters)>AND (<dataset_item_filters>)<endif>
            ORDER BY (workspace_id, dataset_id, id) DESC
            LIMIT :limit OFFSET :offset
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_DATASET_ITEMS_COUNT = """
            WITH items AS (
                SELECT
                    id,
                    column_types
                FROM dataset_items FINAL
                WHERE dataset_id = :datasetId
                AND workspace_id = :workspace_id
                <if(dataset_item_filters)>AND (<dataset_item_filters>)<endif>
            )
            SELECT
                (SELECT count(id) FROM items) AS count,
                mapFromArrays(
                    groupArray(key),
                    groupArray(types)
                ) AS columns
            FROM (
                SELECT
                    key,
                    arrayDistinct(groupArray(type)) AS types
                FROM items
                ARRAY JOIN mapKeys(column_types) AS key
                ARRAY JOIN column_types[key] AS type
                GROUP BY key
            )
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_DATASET_ITEMS_COLUMNS_BY_DATASET_ID = """
            SELECT
                mapFromArrays(
                    groupArray(key),
                    groupArray(types)
                ) AS columns
            FROM (
                SELECT
                    key,
                    arrayDistinct(groupArray(type)) AS types
                FROM (
                    SELECT
                        id,
                        column_types
                    FROM dataset_items FINAL
                    WHERE dataset_id = :datasetId
                    AND workspace_id = :workspace_id
                )
                ARRAY JOIN mapKeys(column_types) AS key
                ARRAY JOIN column_types[key] AS type
                GROUP BY key
            )
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String FIND_DATASET_ITEMS_SUMMARY_BY_DATASET_IDS = """
            SELECT
                dataset_id,
                count(DISTINCT id) AS count
            FROM dataset_items
            WHERE dataset_id IN :dataset_ids
            AND workspace_id = :workspace_id
            GROUP BY dataset_id
            ;
            """;

    /**
     * Counts dataset items only if there's a matching experiment item.
     */
    private static final String SELECT_DATASET_ITEMS_WITH_EXPERIMENT_ITEMS_COUNT = """
            WITH feedback_scores_combined_raw AS (
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
                UNION ALL
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       last_updated_at,
                       author
                 FROM authored_feedback_scores FINAL
                 WHERE entity_type = 'trace'
                   AND workspace_id = :workspace_id
             ),
             feedback_scores_with_ranking AS (
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
             ),
             feedback_scores_combined AS (
                 SELECT workspace_id,
                        project_id,
                        entity_id,
                        name,
                        value,
                        last_updated_at
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
            , fsc AS (
                SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
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
            SELECT
               COUNT(DISTINCT ei.dataset_item_id) AS count
            FROM (
                SELECT
                    dataset_item_id,
                    trace_id
                FROM experiment_items ei
                WHERE workspace_id = :workspace_id
                AND experiment_id IN :experimentIds
                ORDER BY (workspace_id, experiment_id, dataset_item_id, trace_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS ei
            LEFT JOIN (
                SELECT
                    id,
                    data
                FROM dataset_items
                WHERE dataset_id = :datasetId
                AND workspace_id = :workspace_id
                ORDER BY (workspace_id, dataset_id, source, trace_id, span_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS di ON di.id = ei.dataset_item_id
            <if(experiment_item_filters || feedback_scores_filters || feedback_scores_empty_filters)>
            INNER JOIN (
                SELECT
                    id
                FROM traces
                <if(feedback_scores_empty_filters)>
                    LEFT JOIN fsc ON fsc.entity_id = traces.id
                <endif>
                WHERE workspace_id = :workspace_id
                <if(experiment_item_filters)>
                AND <experiment_item_filters>
                <endif>
                <if(feedback_scores_filters)>
                AND id in (
                    SELECT
                        entity_id
                    FROM (
                        SELECT *
                        FROM feedback_scores_final
                        ORDER BY (workspace_id, project_id, entity_id, name) DESC, last_updated_at DESC
                        LIMIT 1 BY entity_id, name
                    )
                    GROUP BY entity_id
                    HAVING <feedback_scores_filters>
                )
                <endif>
                <if(feedback_scores_empty_filters)>
                AND fsc.feedback_scores_count = 0
                <endif>
                ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS tfs ON ei.trace_id = tfs.id
            <endif>
            <if(dataset_item_filters || search_filter)>
            WHERE 1=1
            <if(dataset_item_filters)>
            AND <dataset_item_filters>
            <endif>
            <if(search_filter)>
            AND <search_filter>
            <endif>
            <endif>
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_DATASET_WORKSPACE_ITEMS = """
            SELECT
                DISTINCT id, workspace_id
            FROM dataset_items
            WHERE id IN :datasetItemIds
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    /**
     * Gets the following relationships:
     * - dataset_item - experiment_items -> 1:N
     * - experiment_item - trace -> 1:1
     * - trace - feedback_scores -> 1:N
     * And groups everything together resembling the following rough JSON structure:
     *  {
     *      "dataset_item" : {
     *          "id": "some_id",
     *          ...
     *          "experiment_items": [
     *            {
     *                "id": "some_id",
     *                "input": "trace_input_value",
     *                "output": "trace_output_value",
     *                "feedback_scores": [
     *                  {
     *                    "name": "some_name",
     *                    ...
     *                  },
     *                  ...
     *                ]
     *            },
     *            ...
     *          ]
     *          "
     *      }
     *  }
     */
    private static final String SELECT_DATASET_ITEMS_WITH_EXPERIMENT_ITEMS = """
            WITH experiment_items_scope as (
                SELECT
                    *
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id IN :experimentIds
                ORDER BY (workspace_id, experiment_id, dataset_item_id, trace_id, id) DESC, last_updated_at DESC
            	LIMIT 1 BY id
            ), dataset_items_final AS (
            	SELECT * FROM dataset_items
            	WHERE workspace_id = :workspace_id
            	AND id IN (SELECT dataset_item_id FROM experiment_items_scope)
            	ORDER BY id DESC, last_updated_at DESC
            	LIMIT 1 BY id
            ), feedback_scores_combined_raw AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    category_name,
                    value,
                    reason,
                    source,
                    created_by,
                    last_updated_by,
                    created_at,
                    last_updated_at,
                    feedback_scores.last_updated_by AS author
                FROM feedback_scores FINAL
                WHERE entity_type = :entityType
                  AND workspace_id = :workspace_id
                  AND entity_id IN (SELECT trace_id FROM experiment_items_scope)
                UNION ALL
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    category_name,
                    value,
                    reason,
                    source,
                    created_by,
                    last_updated_by,
                    created_at,
                    last_updated_at,
                    author
                FROM authored_feedback_scores FINAL
                WHERE entity_type = :entityType
                  AND workspace_id = :workspace_id
                  AND entity_id IN (SELECT trace_id FROM experiment_items_scope)
            ),
            feedback_scores_with_ranking AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       category_name,
                       value,
                       reason,
                       source,
                       created_by,
                       last_updated_by,
                       created_at,
                       last_updated_at,
                       author,
                       ROW_NUMBER() OVER (
                           PARTITION BY workspace_id, project_id, entity_id, name, author
                           ORDER BY last_updated_at DESC
                       ) as rn
                FROM feedback_scores_combined_raw
            ),
            feedback_scores_combined AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       category_name,
                       value,
                       reason,
                       source,
                       created_by,
                       last_updated_by,
                       created_at,
                       last_updated_at,
                       author
                FROM feedback_scores_with_ranking
                WHERE rn = 1
            ), feedback_scores_combined_grouped AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    groupArray(value) AS values,
                    groupArray(reason) AS reasons,
                    groupArray(category_name) AS categories,
                    groupArray(author) AS authors,
                    groupArray(source) AS sources,
                    groupArray(created_by) AS created_bies,
                    groupArray(last_updated_by) AS updated_bies,
                    groupArray(created_at) AS created_ats,
                    groupArray(last_updated_at) AS last_updated_ats
                FROM feedback_scores_combined
                GROUP BY workspace_id, project_id, entity_id, name
            ), feedback_scores_final AS (
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    arrayStringConcat(categories, ', ') AS category_name,
                    IF(length(values) = 1, arrayElement(values, 1), toDecimal64(arrayAvg(values), 9)) AS value,
                    IF(length(reasons) = 1, arrayElement(reasons, 1), arrayStringConcat(arrayMap(x -> if(x = '', '\\<no reason>', x), reasons), ', ')) AS reason,
                    arrayElement(sources, 1) AS source,
                    mapFromArrays(
                        authors,
                        arrayMap(
                            i -> tuple(values[i], reasons[i], categories[i], sources[i], last_updated_ats[i]),
                            arrayEnumerate(values)
                        )
                    ) AS value_by_author,
                    arrayStringConcat(created_bies, ', ') AS created_by,
                    arrayStringConcat(updated_bies, ', ') AS last_updated_by,
                    arrayMin(created_ats) AS created_at,
                    arrayMax(last_updated_ats) AS last_updated_at
                FROM feedback_scores_combined_grouped
            ), comments_final AS (
                SELECT
                    id AS comment_id,
                    text,
                    created_at AS comment_created_at,
                    last_updated_at AS comment_last_updated_at,
                    created_by AS comment_created_by,
                    last_updated_by AS comment_last_updated_by,
                    entity_id
                FROM comments
                WHERE workspace_id = :workspace_id
                AND entity_id IN (SELECT trace_id FROM experiment_items_scope)
                ORDER BY (workspace_id, project_id, entity_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ),
            <if(feedback_scores_empty_filters)>
            fsc AS (SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                 FROM feedback_scores_final
                 GROUP BY entity_id
                 HAVING <feedback_scores_empty_filters>
            ),
            <endif>
            experiment_items_final AS (
            	SELECT
            		ei.*
            	FROM experiment_items_scope ei
            	WHERE workspace_id = :workspace_id
            	<if(experiment_item_filters || feedback_scores_filters || feedback_scores_empty_filters)>
                AND trace_id IN (
                    SELECT
                        id
                    FROM traces
                    <if(feedback_scores_empty_filters)>
                        LEFT JOIN fsc ON fsc.entity_id = traces.id
                    <endif>
                    WHERE workspace_id = :workspace_id
                    <if(experiment_item_filters)>
                    AND <experiment_item_filters>
                    <endif>
                    <if(feedback_scores_filters)>
                    AND id IN (
                        SELECT
                            entity_id
                        FROM feedback_scores_final
                        GROUP BY entity_id
                        HAVING <feedback_scores_filters>
                    )
                    <endif>
                    <if(feedback_scores_empty_filters)>
                    AND fsc.feedback_scores_count = 0
                    <endif>
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
                <endif>
            	ORDER BY id DESC, last_updated_at DESC
            )
            SELECT
                ei.dataset_item_id AS id,
                :datasetId AS dataset_id,
                <if(truncate)> mapApply((k, v) -> (k, substring(replaceRegexpAll(v, '<truncate>', '"[image]"'), 1, <truncationSize>)), COALESCE(di.data, map())) <else> COALESCE(di.data, map()) <endif> AS data_final,
                COALESCE(di.data, map()) AS data,
                di.trace_id AS trace_id,
                di.span_id AS span_id,
                di.source AS source,
                di.tags AS tags,
                di.created_at AS created_at,
                di.last_updated_at AS last_updated_at,
                di.created_by AS created_by,
                di.last_updated_by AS last_updated_by,
                argMax(tfs.duration, ei.created_at) AS duration,
                argMax(tfs.total_estimated_cost, ei.created_at) AS total_estimated_cost,
                argMax(tfs.usage, ei.created_at) AS usage,
                argMax(tfs.feedback_scores, ei.created_at) AS feedback_scores,
                argMax(tfs.input, ei.created_at) AS input,
                argMax(tfs.output, ei.created_at) AS output,
                argMax(tfs.metadata, ei.created_at) AS metadata,
                argMax(tfs.visibility_mode, ei.created_at) AS visibility_mode,
                argMax(tfs.comments_array_agg, ei.created_at) AS comments,
                groupArray(tuple(
                    ei.id,
                    ei.experiment_id,
                    ei.dataset_item_id,
                    ei.trace_id,
                    tfs.input,
                    tfs.output,
                    tfs.feedback_scores_array,
                    ei.created_at,
                    ei.last_updated_at,
                    ei.created_by,
                    ei.last_updated_by,
                    tfs.comments_array_agg,
                    tfs.duration,
                    tfs.total_estimated_cost,
                    tfs.usage,
                    tfs.visibility_mode,
                    tfs.metadata
                )) AS experiment_items_array
            FROM experiment_items_final AS ei
            LEFT JOIN dataset_items_final AS di ON di.id = ei.dataset_item_id
            LEFT JOIN (
                SELECT
                    t.id,
                    t.input,
                    t.output,
                    t.metadata,
                    t.duration,
                    t.visibility_mode,
                    s.total_estimated_cost,
                    s.usage,
                    groupUniqArray(tuple(
                        fs.entity_id,
                        fs.name,
                        fs.category_name,
                        fs.value,
                        fs.reason,
                        fs.source,
                        fs.created_at,
                        fs.last_updated_at,
                        fs.created_by,
                        fs.last_updated_by,
                        fs.value_by_author
                    )) AS feedback_scores_array,
                    mapFromArrays(
                        groupArray(fs.name),
                        groupArray(fs.value)
                    ) AS feedback_scores,
                    groupUniqArray(tuple(c.*)) AS comments_array_agg
                FROM (
                    SELECT
                        id,
                       if(end_time IS NOT NULL AND start_time IS NOT NULL
                                             AND notEquals(start_time, toDateTime64('1970-01-01 00:00:00.000', 9)),
                                         (dateDiff('microsecond', start_time, end_time) / 1000.0),
                                         NULL) AS duration,
                        <if(truncate)> replaceRegexpAll(if(notEmpty(input_slim), input_slim, truncated_input), '<truncate>', '"[image]"') as input <else> input <endif>,
                        <if(truncate)> replaceRegexpAll(if(notEmpty(output_slim), output_slim, truncated_output), '<truncate>', '"[image]"') as output <else> output <endif>,
                        metadata,
                        visibility_mode
                    FROM traces
                    WHERE workspace_id = :workspace_id
                    AND id IN (SELECT trace_id FROM experiment_items_final)
                    ORDER BY (workspace_id, project_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                ) AS t
                LEFT JOIN feedback_scores_final AS fs ON t.id = fs.entity_id
                LEFT JOIN comments_final AS c ON t.id = c.entity_id
                LEFT JOIN (
                SELECT
                    trace_id,
                    SUM(total_estimated_cost) AS total_estimated_cost,
                    sumMap(usage) AS usage
                FROM spans final
                WHERE workspace_id = :workspace_id
                AND trace_id IN (SELECT trace_id FROM experiment_items_scope)
                GROUP BY workspace_id, trace_id
                ) s ON t.id = s.trace_id
                GROUP BY
                    t.id,
                    t.input,
                    t.output,
                    t.metadata,
                    t.duration,
                    t.visibility_mode,
                    s.total_estimated_cost,
                    s.usage
            ) AS tfs ON ei.trace_id = tfs.id
            GROUP BY
                ei.dataset_item_id,
                :datasetId,
                COALESCE(di.data, map()),
                di.trace_id,
                di.span_id,
                di.source,
                di.tags,
                di.created_at,
                di.last_updated_at,
                di.created_by,
                di.last_updated_by
            <if(dataset_item_filters || search_filter)>
            HAVING 1=1
            <if(dataset_item_filters)>
            AND <dataset_item_filters>
            <endif>
            <if(search_filter)>
            AND <search_filter>
            <endif>
            <endif>
            <if(sort_fields)>
            ORDER BY <sort_fields>
            <else>
            ORDER BY id DESC, last_updated_at DESC
            <endif>
            LIMIT :limit OFFSET :offset
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    public static final String DATASET_ITEMS = "dataset_items";
    public static final String CLICKHOUSE = "Clickhouse";

    private static final String SELECT_DATASET_EXPERIMENT_ITEMS_COLUMNS_BY_DATASET_ID = """
            WITH dataset_item_final AS (
                SELECT
                    DISTINCT id
                FROM dataset_items
                WHERE workspace_id = :workspace_id
                AND dataset_id = :dataset_id
            ), experiment_items_final AS (
                SELECT DISTINCT
                    ei.trace_id,
                    ei.dataset_item_id
                FROM experiment_items ei
                WHERE workspace_id = :workspace_id
                AND ei.dataset_item_id IN (SELECT id FROM dataset_item_final)
                <if(experiment_ids)> AND ei.experiment_id IN :experiment_ids <endif>
                ORDER BY (workspace_id, experiment_id, dataset_item_id, trace_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            )
            SELECT
                arrayFold(
                    (acc, x) -> mapFromArrays(
                        arrayMap(key -> key, arrayDistinct(arrayConcat(mapKeys(acc), mapKeys(x)))),
                        arrayMap(
                            key -> arrayDistinct(arrayConcat(acc[key], x[key])),
                            arrayDistinct(arrayConcat(mapKeys(acc), mapKeys(x)))
                        )
                    ),
                    arrayDistinct(
                        arrayFlatten(
                            groupArray(
                                arrayMap(
                                    key_type -> map(tupleElement(key_type, 1), [tupleElement(key_type, 2)]),
                                    output_keys
                                )
                            )
                        )
                    ),
                    CAST(map(), 'Map(String, Array(String))')
                ) AS columns
            FROM dataset_item_final as di
            INNER JOIN experiment_items_final as ei ON ei.dataset_item_id = di.id
            INNER JOIN (
                SELECT
                    id,
                    output_keys
                FROM traces final
                WHERE workspace_id = :workspace_id
                AND id IN (SELECT trace_id FROM experiment_items_final)
            ) as t ON t.id = ei.trace_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String BULK_UPDATE = """
            INSERT INTO dataset_items (
                workspace_id,
                dataset_id,
                source,
                trace_id,
                span_id,
                id,
                input,
                expected_output,
                metadata,
                created_at,
                last_updated_at,
                created_by,
                last_updated_by,
                data,
                tags
            )
            SELECT
                s.workspace_id,
                s.dataset_id,
                s.source,
                s.trace_id,
                s.span_id,
                s.id,
                <if(input)> :input <else> s.input <endif> as input,
                <if(expected_output)> :expected_output <else> s.expected_output <endif> as expected_output,
                <if(metadata)> :metadata <else> s.metadata <endif> as metadata,
                s.created_at,
                now64(9) as last_updated_at,
                s.created_by,
                :user_name as last_updated_by,
                <if(data)> :data <else> s.data <endif> as data,
                """ + TagOperations.tagUpdateFragment("s.tags") + """
                as tags
            FROM dataset_items AS s
            WHERE s.workspace_id = :workspace_id
            <if(ids)> AND s.id IN :ids <endif>
            <if(dataset_id)> AND s.dataset_id = :dataset_id <endif>
            <if(dataset_item_filters)> AND (<dataset_item_filters>) <endif>
            ORDER BY (s.workspace_id, s.dataset_id, s.source, s.trace_id, s.span_id, s.id) DESC, s.last_updated_at DESC
            LIMIT 1 BY s.id
            SETTINGS log_comment = '<log_comment>', short_circuit_function_evaluation = 'force_enable';
            """;

    private static final String SELECT_DATASET_ITEMS_WITH_EXPERIMENT_ITEMS_STATS = """
            WITH experiment_items_filtered AS (
                SELECT
                    ei.id,
                    ei.experiment_id,
                    ei.dataset_item_id,
                    ei.trace_id
                FROM experiment_items ei
                WHERE ei.workspace_id = :workspace_id
                AND ei.dataset_item_id IN (
                    SELECT id
                    FROM dataset_items
                    WHERE workspace_id = :workspace_id
                    AND dataset_id = :dataset_id
                    <if(dataset_item_filters)>
                    AND <dataset_item_filters>
                    <endif>
                )
                <if(experiment_ids)>
                AND ei.experiment_id IN :experiment_ids
                <endif>
                <if(experiment_item_filters)>
                AND ei.trace_id IN (
                    SELECT id FROM traces WHERE workspace_id = :workspace_id AND <experiment_item_filters>
                )
                <endif>
            ), feedback_scores_combined AS (
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
                  AND entity_id IN (SELECT trace_id FROM experiment_items_filtered)
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
                   AND entity_id IN (SELECT trace_id FROM experiment_items_filtered)
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
            )<if(feedback_scores_empty_filters)>,
            fsc AS (
                SELECT entity_id, COUNT(entity_id) AS feedback_scores_count
                FROM feedback_scores_final
                GROUP BY entity_id
                HAVING <feedback_scores_empty_filters>
            )<endif><if(feedback_scores_empty_filters || feedback_scores_filters)>,
            experiment_items_filtered_with_feedback_filter AS (
                SELECT
                    eif.id,
                    eif.experiment_id,
                    eif.dataset_item_id,
                    eif.trace_id
                FROM experiment_items_filtered eif
                WHERE 1=1
                <if(feedback_scores_empty_filters)>
                AND eif.trace_id IN (
                    SELECT id
                    FROM traces
                    LEFT JOIN fsc ON fsc.entity_id = traces.id
                    WHERE workspace_id = :workspace_id
                    AND fsc.feedback_scores_count = 0
                )
                <endif>
                <if(feedback_scores_filters)>
                AND eif.trace_id IN (
                    SELECT entity_id
                    FROM feedback_scores_final
                    GROUP BY entity_id, name
                    HAVING <feedback_scores_filters>
                )
                <endif>
            )
            <endif>,
            experiment_items_final AS (
                SELECT * FROM
                    <if(feedback_scores_empty_filters || feedback_scores_filters)>
                        experiment_items_filtered_with_feedback_filter
                    <else>
                        experiment_items_filtered
                    <endif>
            ), traces_with_cost_and_duration AS (
                SELECT DISTINCT
                    eif.trace_id as trace_id,
                    t.duration as duration,
                    s.total_estimated_cost as total_estimated_cost,
                    s.usage as usage
                FROM experiment_items_final eif
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
                ) AS t ON eif.trace_id = t.id
                LEFT JOIN (
                    SELECT
                        trace_id,
                        sum(total_estimated_cost) as total_estimated_cost,
                        sumMap(usage) as usage
                    FROM spans final
                    WHERE workspace_id = :workspace_id
                    AND trace_id IN (SELECT trace_id FROM experiment_items_final)
                    GROUP BY workspace_id, project_id, trace_id
                ) AS s ON eif.trace_id = s.trace_id
            ), feedback_scores_agg AS (
                SELECT
                    entity_id,
                    mapFromArrays(
                        groupArray(name),
                        groupArray(value)
                    ) AS feedback_scores
                FROM feedback_scores_final
                GROUP BY workspace_id, project_id, entity_id
            ), feedback_scores_percentiles AS (
                SELECT
                    name,
                    quantiles(0.5, 0.9, 0.99)(toFloat64(value)) AS percentiles
                FROM feedback_scores_final
                GROUP BY name
            ), usage_total_tokens_data AS (
                SELECT
                    toFloat64(tc.usage['total_tokens']) AS total_tokens
                FROM traces_with_cost_and_duration tc
                WHERE tc.usage['total_tokens'] IS NOT NULL AND tc.usage['total_tokens'] > 0
            )
            SELECT
                count(DISTINCT ei.id) as experiment_items_count,
                count(DISTINCT tc.trace_id) as trace_count,
                mapFromArrays(
                    ['p50', 'p90', 'p99'],
                    arrayMap(
                      v -> toDecimal64(
                             greatest(
                               least(if(isFinite(v), v, 0), 999999999.999999999),
                               -999999999.999999999
                             ),
                             9
                           ),
                      quantiles(0.5, 0.9, 0.99)(tc.duration)
                    )
                ) AS duration,
                avgMap(f.feedback_scores) AS feedback_scores,
                (SELECT mapFromArrays(
                    groupArray(name),
                    groupArray(mapFromArrays(
                        ['p50', 'p90', 'p99'],
                        arrayMap(v -> toDecimal64(if(isFinite(v), v, 0), 9), percentiles)
                    ))
                ) FROM feedback_scores_percentiles) AS feedback_scores_percentiles,
                avgIf(tc.total_estimated_cost, tc.total_estimated_cost > 0) AS total_estimated_cost_,
                toDecimal128(if(isNaN(total_estimated_cost_), 0, total_estimated_cost_), 12) AS total_estimated_cost_avg,
                sumIf(tc.total_estimated_cost, tc.total_estimated_cost > 0) AS total_estimated_cost_sum_,
                toDecimal128(total_estimated_cost_sum_, 12) AS total_estimated_cost_sum,
                mapFromArrays(
                    ['p50', 'p90', 'p99'],
                    arrayMap(
                      v -> toDecimal128(
                             greatest(
                               least(if(isFinite(toFloat64(v)), toFloat64(v), 0), 999999999.999999999),
                               -999999999.999999999
                             ),
                             12
                           ),
                      quantilesIf(0.5, 0.9, 0.99)(tc.total_estimated_cost, tc.total_estimated_cost > 0)
                    )
                ) AS total_estimated_cost_percentiles,
                avgMap(tc.usage) AS usage,
                mapFromArrays(
                    ['p50', 'p90', 'p99'],
                    arrayMap(
                      v -> toInt64(greatest(least(if(isFinite(v), v, 0), 999999999.999999999), -999999999.999999999)),
                      (SELECT quantiles(0.5, 0.9, 0.99)(total_tokens) FROM usage_total_tokens_data)
                    )
                ) AS usage_total_tokens_percentiles
            FROM experiment_items_final ei
            LEFT JOIN traces_with_cost_and_duration AS tc ON ei.trace_id = tc.trace_id
            LEFT JOIN feedback_scores_agg AS f ON ei.trace_id = f.entity_id
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;
    private final @NonNull OpikConfiguration configuration;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull SortingFactoryDatasets sortingFactory;

    @Override
    @WithSpan
    public Mono<Long> save(@NonNull UUID datasetId, @NonNull List<DatasetItem> items) {

        if (items.isEmpty()) {
            return Mono.empty();
        }

        return asyncTemplate.nonTransaction(connection -> mapAndInsert(
                datasetId, items, connection, INSERT_DATASET_ITEM));
    }

    private Mono<Long> mapAndInsert(
            UUID datasetId, List<DatasetItem> items, Connection connection, String sqlTemplate) {

        return makeMonoContextAware((userName, workspaceId) -> {
            List<QueryItem> queryItems = getQueryItemPlaceHolder(items.size());

            var template = getSTWithLogComment(sqlTemplate, "save_dataset_items", workspaceId, items.size())
                    .add("items", queryItems);

            String sql = template.render();

            var statement = connection.createStatement(sql);
            statement.bind("workspace_id", workspaceId);

            int i = 0;
            for (DatasetItem item : items) {
                Map<String, JsonNode> data = new HashMap<>(Optional.ofNullable(item.data()).orElse(Map.of()));

                statement.bind("id" + i, item.id());
                statement.bind("datasetId" + i, datasetId);
                statement.bind("source" + i, item.source().getValue());
                statement.bind("traceId" + i, DatasetItemResultMapper.getOrDefault(item.traceId()));
                statement.bind("spanId" + i, DatasetItemResultMapper.getOrDefault(item.spanId()));
                statement.bind("data" + i, DatasetItemResultMapper.getOrDefault(data));
                statement.bind("tags" + i, item.tags() != null ? item.tags().toArray(String[]::new) : new String[]{});
                statement.bind("createdBy" + i, userName);
                statement.bind("lastUpdatedBy" + i, userName);
                i++;
            }

            Segment segment = startSegment(DATASET_ITEMS, CLICKHOUSE, "insert_dataset_items");

            return Flux.from(statement.execute())
                    .flatMap(Result::getRowsUpdated)
                    .reduce(0L, Long::sum)
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @Override
    @WithSpan
    public Mono<DatasetItem> get(@NonNull UUID id) {
        return asyncTemplate.nonTransaction(connection -> {

            Statement statement = connection.createStatement(SELECT_DATASET_ITEM)
                    .bind("id", id);

            Segment segment = startSegment(DATASET_ITEMS, CLICKHOUSE, "select_dataset_item");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(DatasetItemResultMapper::mapItem)
                    .singleOrEmpty();
        });
    }

    @Override
    @WithSpan
    public Flux<DatasetItem> getItems(@NonNull UUID datasetId, int limit, UUID lastRetrievedId) {
        return getItems(datasetId, limit, lastRetrievedId, List.of());
    }

    @Override
    @WithSpan
    public Flux<DatasetItem> getItems(@NonNull UUID datasetId, int limit, UUID lastRetrievedId,
            @NonNull List<DatasetItemFilter> filters) {

        return asyncTemplate.stream(connection -> makeFluxContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_DATASET_ITEMS_STREAM, "select_dataset_items_stream", workspaceId,
                    datasetId.toString());

            if (lastRetrievedId != null) {
                template.add("lastRetrievedId", lastRetrievedId);
            }

            // Add filter support
            if (CollectionUtils.isNotEmpty(filters)) {
                FilterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.DATASET_ITEM)
                        .ifPresent(datasetItemFilters -> template.add("dataset_item_filters", datasetItemFilters));
            }

            var statement = connection.createStatement(template.render())
                    .bind("datasetId", datasetId)
                    .bind("limit", limit)
                    .bind("workspace_id", workspaceId);

            if (lastRetrievedId != null) {
                statement.bind("lastRetrievedId", lastRetrievedId);
            }

            // Bind filter parameters
            if (CollectionUtils.isNotEmpty(filters)) {
                FilterQueryBuilder.bind(statement, filters, FilterStrategy.DATASET_ITEM);
            }

            Segment segment = startSegment(DATASET_ITEMS, CLICKHOUSE, "select_dataset_items_stream");

            return Flux.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(DatasetItemResultMapper::mapItem);
        }));
    }

    @Override
    @WithSpan
    public Mono<List<WorkspaceAndResourceId>> getDatasetItemWorkspace(@NonNull Set<UUID> datasetItemIds) {

        if (datasetItemIds.isEmpty()) {
            return Mono.just(List.of());
        }

        return asyncTemplate.nonTransaction(connection -> {

            var statement = connection.createStatement(SELECT_DATASET_WORKSPACE_ITEMS)
                    .bind("datasetItemIds", datasetItemIds.toArray(UUID[]::new));

            return Mono.from(statement.execute())
                    .flatMapMany(result -> result.map((row, rowMetadata) -> new WorkspaceAndResourceId(
                            row.get("workspace_id", String.class),
                            row.get("id", UUID.class))))
                    .collectList();
        });
    }

    @Override
    @WithSpan
    public Flux<DatasetItemSummary> findDatasetItemSummaryByDatasetIds(Set<UUID> datasetIds) {
        if (datasetIds.isEmpty()) {
            return Flux.empty();
        }

        return asyncTemplate.stream(connection -> {

            var statement = connection.createStatement(FIND_DATASET_ITEMS_SUMMARY_BY_DATASET_IDS)
                    .bind("dataset_ids", datasetIds);

            Segment segment = startSegment(DATASET_ITEMS, CLICKHOUSE, "find_dataset_item_summary_by_dataset_ids");

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .doFinally(signalType -> endSegment(segment))
                    .flatMap(result -> result.map((row, rowMetadata) -> DatasetItemSummary
                            .builder()
                            .datasetId(row.get("dataset_id", UUID.class))
                            .datasetItemsCount(row.get("count", Long.class))
                            .build()));
        });
    }

    @Override
    public Mono<List<Column>> getOutputColumns(@NonNull UUID datasetId, Set<UUID> experimentIds) {
        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {

            var template = getSTWithLogComment(SELECT_DATASET_EXPERIMENT_ITEMS_COLUMNS_BY_DATASET_ID,
                    "get_output_columns", workspaceId, datasetId.toString());

            if (CollectionUtils.isNotEmpty(experimentIds)) {
                template.add("experiment_ids", experimentIds);
            }

            var statement = connection.createStatement(template.render())
                    .bind("dataset_id", datasetId)
                    .bind("workspace_id", workspaceId);

            if (CollectionUtils.isNotEmpty(experimentIds)) {
                statement.bind("experiment_ids", experimentIds.toArray(UUID[]::new));
            }

            return Mono.from(statement.execute())
                    .flatMap(result -> DatasetItemResultMapper.mapColumns(result, "output"))
                    .map(List::copyOf);
        }));
    }

    @Override
    @WithSpan
    public Mono<Long> delete(Set<UUID> ids, UUID datasetId, List<DatasetItemFilter> filters) {
        boolean hasIds = CollectionUtils.isNotEmpty(ids);
        boolean hasDatasetId = datasetId != null;

        Preconditions.checkArgument(hasIds ^ hasDatasetId,
                "Either ids or datasetId must be provided, but not both");

        if (hasIds) {
            log.info("Deleting '{}' dataset items by IDs", ids.size());
        } else {
            log.info("Deleting dataset items from dataset '{}' with filters", datasetId);
        }

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(DELETE_DATASET_ITEM, "delete_dataset_items", workspaceId,
                    hasIds ? ids.size() : 0);

            // Add ids or filters to template
            // Delete by specific IDs (mutually exclusive with dataset_id + filters)
            if (hasIds) {
                template.add("ids", true);
            } else {
                // Delete by dataset_id with optional filters (mutually exclusive with ids)
                template.add("dataset_id", true);

                // Add additional filters if provided
                if (CollectionUtils.isNotEmpty(filters)) {
                    Optional<String> datasetItemFiltersOpt = filterQueryBuilder.toAnalyticsDbFilters(filters,
                            FilterStrategy.DATASET_ITEM);

                    datasetItemFiltersOpt.ifPresent(datasetItemFilters -> template.add("dataset_item_filters",
                            datasetItemFilters));
                }
            }

            var query = template.render();
            var statement = connection.createStatement(query)
                    .bind("workspace_id", workspaceId);

            // Bind ids if provided
            if (hasIds) {
                statement.bind("ids", ids.stream().map(UUID::toString).toArray(String[]::new));
            } else {
                statement.bind("dataset_id", datasetId.toString());

                // Bind filter parameters if provided
                if (CollectionUtils.isNotEmpty(filters)) {
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.DATASET_ITEM);
                }
            }

            String segmentOperation = hasIds ? "delete_dataset_items" : "delete_dataset_items_by_filters";
            Segment segment = startSegment(DATASET_ITEMS, CLICKHOUSE, segmentOperation);

            String successMessage = hasIds
                    ? "Completed delete for '%s' dataset items".formatted(ids.size())
                    : "Completed delete for dataset items matching filters";

            return Flux.from(statement.execute())
                    .flatMap(Result::getRowsUpdated)
                    .reduce(0L, Long::sum)
                    .doFinally(signalType -> endSegment(segment))
                    .doOnSuccess(__ -> log.info(successMessage));
        }));
    }

    private ST newFindTemplate(String query, DatasetItemSearchCriteria datasetItemSearchCriteria, String queryName,
            String workspaceId) {
        var template = getSTWithLogComment(query, queryName, workspaceId, "");

        Optional.ofNullable(datasetItemSearchCriteria.filters())
                .ifPresent(filters -> {
                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.DATASET_ITEM)
                            .ifPresent(datasetItemFilters -> template.add("dataset_item_filters", datasetItemFilters));

                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.EXPERIMENT_ITEM)
                            .ifPresent(experimentItemFilters -> template.add("experiment_item_filters",
                                    experimentItemFilters));

                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.FEEDBACK_SCORES)
                            .ifPresent(scoresFilters -> template.add("feedback_scores_filters", scoresFilters));

                    filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY)
                            .ifPresent(feedbackScoreIsEmptyFilters -> template.add("feedback_scores_empty_filters",
                                    feedbackScoreIsEmptyFilters));
                });

        Optional.ofNullable(datasetItemSearchCriteria.search())
                .filter(s -> !s.isBlank())
                .ifPresent(searchText -> template.add("search_filter",
                        filterQueryBuilder.buildDatasetItemSearchFilter(searchText)));

        return template;
    }

    private void bindSearchCriteria(DatasetItemSearchCriteria datasetItemSearchCriteria, Statement statement) {
        Optional.ofNullable(datasetItemSearchCriteria.filters())
                .ifPresent(filters -> {
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.DATASET_ITEM);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.EXPERIMENT_ITEM);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES);
                    filterQueryBuilder.bind(statement, filters, FilterStrategy.FEEDBACK_SCORES_IS_EMPTY);
                });

        Optional.ofNullable(datasetItemSearchCriteria.search())
                .filter(s -> !s.isBlank())
                .ifPresent(searchText -> filterQueryBuilder.bindSearchTerms(statement, searchText));
    }

    @Override
    @WithSpan
    public Mono<DatasetItemPage> getItems(
            @NonNull DatasetItemSearchCriteria datasetItemSearchCriteria, int page, int size) {

        boolean hasExperimentIds = CollectionUtils.isNotEmpty(datasetItemSearchCriteria.experimentIds());

        // Choose the appropriate query and segment names based on experiment IDs
        String query = hasExperimentIds ? SELECT_DATASET_ITEMS_WITH_EXPERIMENT_ITEMS : SELECT_DATASET_ITEMS;
        String summarySegmentName = hasExperimentIds
                ? "select_dataset_items_experiments_filters_summary"
                : "select_dataset_items_filters_summary";
        String contentSegmentName = hasExperimentIds
                ? "select_dataset_items_experiments_filters"
                : "select_dataset_items_filters";

        Segment segment = startSegment(DATASET_ITEMS, CLICKHOUSE, summarySegmentName);

        Mono<Set<Column>> columnsMono = mapColumnsField(datasetItemSearchCriteria);
        Mono<Long> countMono = getCount(datasetItemSearchCriteria);

        return Mono.zip(countMono, columnsMono)
                .doFinally(signalType -> endSegment(segment))
                .flatMap(results -> asyncTemplate
                        .nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {

                            Segment segmentContent = startSegment(DATASET_ITEMS, CLICKHOUSE, contentSegmentName);

                            var selectTemplate = newFindTemplate(query, datasetItemSearchCriteria, contentSegmentName,
                                    workspaceId);
                            selectTemplate = ImageUtils.addTruncateToTemplate(selectTemplate,
                                    datasetItemSearchCriteria.truncate());
                            selectTemplate = selectTemplate.add("truncationSize",
                                    configuration.getResponseFormatting().getTruncationSize());

                            // Add sorting if present
                            var finalTemplate = selectTemplate;
                            if (datasetItemSearchCriteria.sortingFields() != null) {
                                Optional.ofNullable(
                                        sortingQueryBuilder.toOrderBySql(datasetItemSearchCriteria.sortingFields(),
                                                filterQueryBuilder
                                                        .buildDatasetItemFieldMapping(
                                                                datasetItemSearchCriteria.sortingFields())))
                                        .ifPresent(sortFields -> {
                                            // feedback_scores is now exposed at outer query level via argMax(tfs.feedback_scores, ei.created_at)
                                            // so we don't need to map it to tfs.feedback_scores anymore
                                            finalTemplate.add("sort_fields", sortFields);
                                        });
                            }

                            var hasDynamicKeys = datasetItemSearchCriteria.sortingFields() != null
                                    && sortingQueryBuilder.hasDynamicKeys(datasetItemSearchCriteria.sortingFields());

                            var selectStatement = connection.createStatement(finalTemplate.render())
                                    .bind("datasetId", datasetItemSearchCriteria.datasetId())
                                    .bind("limit", size)
                                    .bind("offset", (page - 1) * size)
                                    .bind("workspace_id", workspaceId);

                            // Only bind experimentIds and entityType if we have experiment IDs
                            if (hasExperimentIds) {
                                selectStatement = selectStatement.bind("experimentIds",
                                        datasetItemSearchCriteria.experimentIds().toArray(UUID[]::new))
                                        .bind("entityType", datasetItemSearchCriteria.entityType().getType());
                            }

                            // Bind dynamic sorting keys if present
                            if (hasDynamicKeys) {
                                selectStatement = sortingQueryBuilder.bindDynamicKeys(selectStatement,
                                        datasetItemSearchCriteria.sortingFields());
                            }

                            bindSearchCriteria(datasetItemSearchCriteria, selectStatement);

                            Long total = results.getT1();
                            Set<Column> columns = results.getT2();

                            return Flux.from(selectStatement.execute())
                                    .doFinally(signalType -> endSegment(segmentContent))
                                    .flatMap(DatasetItemResultMapper::mapItem)
                                    .collectList()
                                    .onErrorResume(e -> handleSqlError(e, List.of()))
                                    .flatMap(
                                            items -> Mono
                                                    .just(new DatasetItemPage(items, page, items.size(), total, columns,
                                                            sortingFactory.getSortableFields())));
                        })));
    }

    private Mono<Long> getCount(DatasetItemSearchCriteria datasetItemSearchCriteria) {
        // Choose the appropriate count query based on whether we have experiment IDs
        boolean hasExperimentIds = CollectionUtils.isNotEmpty(datasetItemSearchCriteria.experimentIds());
        String countQuery = hasExperimentIds
                ? SELECT_DATASET_ITEMS_WITH_EXPERIMENT_ITEMS_COUNT
                : SELECT_DATASET_ITEMS_COUNT;

        Segment segment = startSegment(DATASET_ITEMS, CLICKHOUSE, "select_dataset_items_filters_columns");

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {

            var countTemplate = newFindTemplate(countQuery, datasetItemSearchCriteria, "get_count", workspaceId);

            var statement = connection.createStatement(countTemplate.render())
                    .bind("datasetId", datasetItemSearchCriteria.datasetId())
                    .bind("workspace_id", workspaceId);

            // Only bind experimentIds if we have them
            if (hasExperimentIds) {
                statement = statement.bind("experimentIds",
                        datasetItemSearchCriteria.experimentIds().toArray(UUID[]::new));
            }

            bindSearchCriteria(datasetItemSearchCriteria, statement);

            return Flux.from(statement.execute())
                    .flatMap(DatasetItemResultMapper::mapCount)
                    .reduce(0L, Long::sum)
                    .onErrorResume(e -> handleSqlError(e, 0L))
                    .doFinally(signalType -> endSegment(segment));
        }));
    }

    private Mono<Set<Column>> mapColumnsField(DatasetItemSearchCriteria datasetItemSearchCriteria) {
        Segment segment = startSegment(DATASET_ITEMS, CLICKHOUSE, "select_dataset_items_filters_columns");

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_DATASET_ITEMS_COLUMNS_BY_DATASET_ID, "map_columns_field",
                    workspaceId, "");
            return Mono.from(connection.createStatement(template.render())
                    .bind("datasetId", datasetItemSearchCriteria.datasetId())
                    .bind("workspace_id", workspaceId)
                    .execute())
                    .flatMap(result -> DatasetItemResultMapper.mapColumns(result, "data"));
        }))
                .doFinally(signalType -> endSegment(segment));
    }

    private <T> Mono<T> handleSqlError(Throwable e, T defaultValue) {
        if (e instanceof ClickHouseException && e.getMessage().contains("Unable to parse JSONPath. (BAD_ARGUMENTS)")) {
            return Mono.just(defaultValue);
        }
        return Mono.error(e);
    }

    @WithSpan
    public Mono<com.comet.opik.api.ProjectStats> getExperimentItemsStats(
            @NonNull UUID datasetId,
            @NonNull Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters) {
        log.info("Getting experiment items stats for dataset '{}' and experiments '{}' with filters '{}'", datasetId,
                experimentIds, filters);

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_DATASET_ITEMS_WITH_EXPERIMENT_ITEMS_STATS,
                    "get_experiment_items_stats", workspaceId, experimentIds.size());
            template.add("dataset_id", datasetId);
            if (!experimentIds.isEmpty()) {
                template.add("experiment_ids", true);
            }

            applyFiltersToTemplate(template, filters);

            String sql = template.render();
            log.debug("Experiment items stats query: '{}'", sql);

            Statement statement = connection.createStatement(sql)
                    .bind("workspace_id", workspaceId);
            bindStatementParameters(statement, datasetId, experimentIds, filters);

            return Flux.from(statement.execute())
                    .flatMap(result -> Flux.from(result.map(
                            (row, rowMetadata) -> com.comet.opik.domain.stats.StatsMapper
                                    .mapExperimentItemsStats(row))))
                    .singleOrEmpty();
        }))
                .doOnError(error -> log.error("Failed to get experiment items stats", error));
    }

    private void applyFiltersToTemplate(ST template, List<ExperimentsComparisonFilter> filters) {
        Optional.ofNullable(filters)
                .ifPresent(filtersParam -> {
                    filterQueryBuilder.toAnalyticsDbFilters(filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.EXPERIMENT_ITEM)
                            .ifPresent(experimentItemFilters -> template.add("experiment_item_filters",
                                    experimentItemFilters));

                    filterQueryBuilder.toAnalyticsDbFilters(filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.FEEDBACK_SCORES)
                            .ifPresent(feedbackScoresFilters -> template.add("feedback_scores_filters",
                                    feedbackScoresFilters));

                    filterQueryBuilder.toAnalyticsDbFilters(filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.FEEDBACK_SCORES_IS_EMPTY)
                            .ifPresent(feedbackScoresEmptyFilters -> template.add("feedback_scores_empty_filters",
                                    feedbackScoresEmptyFilters));

                    filterQueryBuilder.toAnalyticsDbFilters(filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.DATASET_ITEM)
                            .ifPresent(datasetItemFilters -> template.add("dataset_item_filters",
                                    datasetItemFilters));
                });
    }

    private void bindStatementParameters(Statement statement, UUID datasetId, Set<UUID> experimentIds,
            List<ExperimentsComparisonFilter> filters) {
        statement.bind("dataset_id", datasetId);
        if (!experimentIds.isEmpty()) {
            statement.bind("experiment_ids", experimentIds.toArray(UUID[]::new));
        }

        Optional.ofNullable(filters)
                .ifPresent(filtersParam -> {
                    // Bind all filters - the builder will handle both regular and aggregated filters
                    filterQueryBuilder.bind(statement, filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.EXPERIMENT_ITEM);
                    filterQueryBuilder.bind(statement, filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.FEEDBACK_SCORES);
                    filterQueryBuilder.bind(statement, filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.FEEDBACK_SCORES_IS_EMPTY);
                    filterQueryBuilder.bind(statement, filtersParam,
                            com.comet.opik.domain.filter.FilterStrategy.DATASET_ITEM);
                });
    }

    @Override
    @WithSpan
    public Mono<Void> bulkUpdate(Set<UUID> ids, UUID datasetId, List<DatasetItemFilter> filters,
            @NonNull DatasetItemUpdate update, boolean mergeTags) {
        boolean hasIds = CollectionUtils.isNotEmpty(ids);
        // Empty filters array means "select all items", null means not provided
        boolean hasFilters = filters != null;

        Preconditions.checkArgument(hasIds || hasFilters, "Either ids or filters must be provided");

        if (hasIds) {
            log.info("Bulk updating '{}' dataset items by IDs", ids.size());
        } else {
            log.info("Bulk updating dataset items by filters for dataset '{}'", datasetId);
        }

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = newBulkUpdateTemplate(update, BULK_UPDATE, mergeTags, "bulk_update", workspaceId);

            // Add ids or filters to template
            if (hasIds) {
                template.add("ids", true);
            } else {
                // When using filters, dataset_id is required
                template.add("dataset_id", true);
                filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.DATASET_ITEM)
                        .ifPresent(datasetItemFilters -> template.add("dataset_item_filters", datasetItemFilters));
            }

            var query = template.render();
            var statement = connection.createStatement(query)
                    .bind("user_name", userName)
                    .bind("workspace_id", workspaceId);

            // Bind ids if provided
            if (hasIds) {
                statement.bind("ids", ids);
            } else {
                // Bind dataset_id when using filters
                statement.bind("dataset_id", datasetId);
            }

            bindBulkUpdateParams(update, statement);

            // Bind filter parameters if provided
            if (hasFilters) {
                filterQueryBuilder.bind(statement, filters, FilterStrategy.DATASET_ITEM);
            }

            String segmentOperation = hasIds ? "bulk_update" : "bulk_update_by_filters";
            Segment segment = startSegment("dataset_items", "Clickhouse", segmentOperation);

            String successMessage = hasIds
                    ? "Completed bulk update for '%s' dataset items".formatted(ids.size())
                    : "Completed bulk update for dataset items matching filters in dataset '%s'".formatted(datasetId);

            return Flux.from(statement.execute())
                    .doFinally(signalType -> endSegment(segment))
                    .then()
                    .doOnSuccess(__ -> log.info(successMessage));
        }));
    }

    private ST newBulkUpdateTemplate(com.comet.opik.api.DatasetItemUpdate update, String sql, boolean mergeTags,
            String queryName, String workspaceId) {
        var template = getSTWithLogComment(sql, queryName, workspaceId, "");

        Optional.ofNullable(update.input())
                .ifPresent(input -> template.add("input", input));
        Optional.ofNullable(update.expectedOutput())
                .ifPresent(expectedOutput -> template.add("expected_output", expectedOutput));
        Optional.ofNullable(update.metadata())
                .ifPresent(metadata -> template.add("metadata", metadata.toString()));
        Optional.ofNullable(update.data())
                .ifPresent(data -> template.add("data", data.toString()));

        TagOperations.configureTagTemplate(template, update, mergeTags);

        return template;
    }

    private void bindBulkUpdateParams(com.comet.opik.api.DatasetItemUpdate update, Statement statement) {
        Optional.ofNullable(update.input())
                .ifPresent(input -> statement.bind("input", input));
        Optional.ofNullable(update.expectedOutput())
                .ifPresent(expectedOutput -> statement.bind("expected_output", expectedOutput));
        Optional.ofNullable(update.metadata())
                .ifPresent(metadata -> statement.bind("metadata", DatasetItemResultMapper.getOrDefault(metadata)));
        Optional.ofNullable(update.data())
                .ifPresent(data -> statement.bind("data", DatasetItemResultMapper.getOrDefault(data)));

        TagOperations.bindTagParams(statement, update);
    }

    @Override
    @WithSpan
    public Mono<Map<String, List<String>>> getColumns(@NonNull UUID datasetId) {
        log.debug("Getting columns for dataset '{}'", datasetId);

        Segment segment = startSegment(DATASET_ITEMS, CLICKHOUSE, "select_dataset_items_columns");

        return asyncTemplate.nonTransaction(connection -> {
            Statement statement = connection.createStatement(SELECT_DATASET_ITEMS_COLUMNS_BY_DATASET_ID)
                    .bind("datasetId", datasetId);

            Flux<Map<String, List<String>>> resultFlux = makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .flatMap(result -> result.map((row, rowMetadata) -> {
                        @SuppressWarnings("unchecked")
                        Map<String, List<String>> columns = (Map<String, List<String>>) row.get("columns", Map.class);
                        // Use LinkedHashMap to preserve insertion order
                        return columns != null
                                ? new LinkedHashMap<>(columns)
                                : new LinkedHashMap<String, List<String>>();
                    }));

            return resultFlux.defaultIfEmpty(new LinkedHashMap<String, List<String>>()).next();
        })
                .doFinally(signalType -> endSegment(segment));
    }
}
