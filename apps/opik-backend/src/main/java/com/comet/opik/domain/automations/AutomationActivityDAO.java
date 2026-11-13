package com.comet.opik.domain.automations;

import com.google.inject.ImplementedBy;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;

/**
 * What happened to an automation's targets since an instant: the counts the counted triggers fire on,
 * and the judge-versus-human score pairs judge alignment is measured on. All ClickHouse.
 */
@ImplementedBy(AutomationActivityDAOImpl.class)
public interface AutomationActivityDAO {

    /** Items added to a dataset since the instant, versioned or not. */
    Mono<Long> countDatasetItemsSince(UUID datasetId, Instant since);

    /** Distinct items a reviewer scored through the queue since the instant. */
    Mono<Long> countAnnotatedSince(UUID queueId, UUID projectId, Instant since);

    /** One row per trace the judge and a reviewer both scored under the name, with both values. */
    Flux<JudgeHumanPair> judgeHumanPairs(UUID projectId, UUID queueId, String scoreName, Instant since, int limit);

    @Builder(toBuilder = true)
    record JudgeHumanPair(@NonNull UUID entityId, @NonNull BigDecimal judge, @NonNull BigDecimal human) {
    }
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AutomationActivityDAOImpl implements AutomationActivityDAO {

    // Versioned items live in dataset_item_versions, drafts and unversioned ones in dataset_items; an
    // item is counted once whichever table holds it.
    private static final String COUNT_DATASET_ITEMS_SINCE = """
            SELECT count(DISTINCT id) AS count
            FROM (
                SELECT dataset_item_id AS id
                FROM dataset_item_versions
                WHERE workspace_id = :workspace_id
                  AND dataset_id = :dataset_id
                  AND item_created_at >= parseDateTime64BestEffort(:since, 9)
                UNION ALL
                SELECT id
                FROM dataset_items
                WHERE workspace_id = :workspace_id
                  AND dataset_id = :dataset_id
                  AND created_at >= parseDateTime64BestEffort(:since, 9)
            )
            """;

    private static final String COUNT_ANNOTATED_SINCE = """
            SELECT uniqExact(entity_id) AS count
            FROM authored_feedback_scores
            WHERE workspace_id = :workspace_id
              AND project_id = :project_id
              AND source_queue_id = :queue_id
              AND source = 'ui'
              AND created_at >= parseDateTime64BestEffort(:since, 9)
            """;

    // The judge is the online scoring row, the human the queue's ui row; latest per author wins.
    private static final String JUDGE_HUMAN_PAIRS = """
            WITH latest AS (
                SELECT entity_id, source, value, author, source_queue_id, last_updated_at
                FROM authored_feedback_scores
                WHERE workspace_id = :workspace_id
                  AND project_id = :project_id
                  AND entity_type = 'trace'
                  AND name = :name
                  AND created_at >= parseDateTime64BestEffort(:since, 9)
                ORDER BY last_updated_at DESC
                LIMIT 1 BY entity_id, author, source_queue_id
            )
            SELECT judge.entity_id AS entity_id,
                   any(judge.value) AS judge_value,
                   avg(human.value) AS human_value
            FROM latest judge
            JOIN latest human ON human.entity_id = judge.entity_id
            WHERE judge.source = 'online_scoring'
              AND human.source = 'ui'
              AND human.source_queue_id = :queue_id
            GROUP BY judge.entity_id
            LIMIT :limit
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    @Override
    public Mono<Long> countDatasetItemsSince(@NonNull UUID datasetId, @NonNull Instant since) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> makeFluxContextAware(bindWorkspaceIdToFlux(
                        connection.createStatement(COUNT_DATASET_ITEMS_SINCE)
                                .bind("dataset_id", datasetId.toString())
                                .bind("since", since.toString()))))
                .flatMap(result -> result.map((row, metadata) -> row.get("count", Long.class)))
                .reduce(0L, Long::sum);
    }

    @Override
    public Mono<Long> countAnnotatedSince(@NonNull UUID queueId, @NonNull UUID projectId, @NonNull Instant since) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> makeFluxContextAware(bindWorkspaceIdToFlux(
                        connection.createStatement(COUNT_ANNOTATED_SINCE)
                                .bind("project_id", projectId.toString())
                                .bind("queue_id", queueId.toString())
                                .bind("since", since.toString()))))
                .flatMap(result -> result.map((row, metadata) -> row.get("count", Long.class)))
                .reduce(0L, Long::sum);
    }

    @Override
    public Flux<JudgeHumanPair> judgeHumanPairs(@NonNull UUID projectId, @NonNull UUID queueId,
            @NonNull String scoreName, @NonNull Instant since, int limit) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> makeFluxContextAware(bindWorkspaceIdToFlux(
                        connection.createStatement(JUDGE_HUMAN_PAIRS)
                                .bind("project_id", projectId.toString())
                                .bind("queue_id", queueId.toString())
                                .bind("name", scoreName)
                                .bind("since", since.toString())
                                .bind("limit", limit))))
                .flatMap(result -> result.map((row, metadata) -> JudgeHumanPair.builder()
                        .entityId(UUID.fromString(row.get("entity_id", String.class)))
                        .judge(row.get("judge_value", BigDecimal.class))
                        .human(row.get("human_value", BigDecimal.class))
                        .build()));
    }
}
