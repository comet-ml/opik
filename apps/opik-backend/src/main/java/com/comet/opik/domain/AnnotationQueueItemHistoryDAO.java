package com.comet.opik.domain;

import com.comet.opik.utils.template.TemplateUtils;
import com.google.common.annotations.VisibleForTesting;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContext;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;

/**
 * Every item a queue has ever held, which is what stops automation re-adding one a reviewer removed.
 *
 * <p>Its own entity and its own DAO: {@code annotation_queue_items} answers what a queue holds now, this
 * answers what it has ever held, and the two have different lifetimes — removing an item clears the first
 * and leaves the second. Ordering the two writes is the service's job, since the guarantee is about how
 * they relate rather than about either table.
 */
@ImplementedBy(AnnotationQueueItemHistoryDAOImpl.class)
public interface AnnotationQueueItemHistoryDAO {

    Mono<Long> recordItems(UUID queueId, Set<UUID> itemIds, UUID projectId);

    Mono<Set<UUID>> findPreviouslyAddedItems(UUID queueId, UUID projectId, Set<UUID> itemIds);

    /**
     * Deletes the ledger for these queues, taking the project each one belongs to.
     *
     * <p>Keyed by queue rather than given two independent lists: the predicate is scoped by project to stay
     * on the sort key, and two lists describe every combination of the two, so one queue could be matched
     * against another's project.
     */
    Mono<Long> deleteByQueueIds(Map<UUID, UUID> projectIdByQueueId);

    /**
     * How many items this queue has ever held.
     *
     * <p>Nothing in production asks this — the history is only ever queried for a specific set of item
     * ids. It exists so a test can assert the ledger's contents through the DAO that owns it rather than
     * by reaching into ClickHouse itself.
     */
    @VisibleForTesting
    Mono<Long> countByQueueId(UUID queueId, UUID projectId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class AnnotationQueueItemHistoryDAOImpl implements AnnotationQueueItemHistoryDAO {

    private static final String BATCH_INSERT = """
            INSERT INTO annotation_queue_item_history (
                queue_id,
                item_id,
                project_id,
                workspace_id,
                created_by,
                last_updated_by
            )
            VALUES
                <items:{item |
                    (
                        :queue_id,
                        :item_id<item.index>,
                        :project_id,
                        :workspace_id,
                        :user_name,
                        :user_name
                    )
                    <if(item.hasNext)>,<endif>
                }>
            """;

    // Which of these items have ever been in this queue. The caller supplies a bounded id set, so
    // fetching the intersection and subtracting is simpler than a NOT IN subquery and does the same job.
    private static final String SELECT_PREVIOUSLY_ADDED = """
            SELECT DISTINCT item_id
            FROM annotation_queue_item_history
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND queue_id = :queue_id
            AND item_id IN :item_ids
            """;

    // Scoped by project as well as queue because the sort key is (workspace_id, project_id, queue_id,
    // item_id): without project_id the predicate cannot use the key past workspace_id, turning a queue
    // deletion into a scan of every history row in the workspace.
    private static final String COUNT_BY_QUEUE_ID = """
            SELECT count(DISTINCT item_id) AS count
            FROM annotation_queue_item_history
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND queue_id = :queue_id
            """;

    private static final String DELETE_BY_QUEUE_IDS = """
            DELETE FROM annotation_queue_item_history
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND queue_id IN :ids
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    @Override
    public Mono<Long> recordItems(@NonNull UUID queueId, @NonNull Set<UUID> itemIds, @NonNull UUID projectId) {
        if (itemIds.isEmpty()) {
            return Mono.just(0L);
        }

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var template = TemplateUtils.newST(BATCH_INSERT)
                            .add("items", getQueryItemPlaceHolder(itemIds.size()));

                    Statement statement = connection.createStatement(template.render())
                            .bind("queue_id", queueId.toString())
                            .bind("project_id", projectId.toString());

                    int index = 0;
                    for (UUID itemId : itemIds) {
                        statement.bind("item_id" + index, itemId.toString());
                        index++;
                    }

                    return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement));
                })
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum);
    }

    @Override
    public Mono<Set<UUID>> findPreviouslyAddedItems(@NonNull UUID queueId, @NonNull UUID projectId,
            @NonNull Set<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return Mono.just(Set.of());
        }

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(SELECT_PREVIOUSLY_ADDED)
                            .bind("project_id", projectId.toString())
                            .bind("queue_id", queueId.toString())
                            .bind("item_ids", itemIds.toArray(UUID[]::new));

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map(
                        (row, metadata) -> UUID.fromString(row.get("item_id", String.class))))
                .collect(Collectors.toSet());
    }

    @Override
    public Mono<Long> countByQueueId(@NonNull UUID queueId, @NonNull UUID projectId) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(COUNT_BY_QUEUE_ID)
                            .bind("project_id", projectId.toString())
                            .bind("queue_id", queueId.toString());

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, metadata) -> row.get("count", Long.class)))
                .reduce(0L, Long::sum);
    }

    @Override
    public Mono<Long> deleteByQueueIds(@NonNull Map<UUID, UUID> projectIdByQueueId) {
        if (projectIdByQueueId.isEmpty()) {
            return Mono.just(0L);
        }

        // One statement per project, each carrying only its own queues, so a queue is never deleted under a
        // project it does not belong to. The batch being deleted bounds how many that is.
        Map<UUID, Set<UUID>> queueIdsByProject = projectIdByQueueId.entrySet().stream()
                .collect(Collectors.groupingBy(Map.Entry::getValue,
                        Collectors.mapping(Map.Entry::getKey, Collectors.toSet())));

        return Flux.fromIterable(queueIdsByProject.entrySet())
                .concatMap(entry -> Mono.from(connectionFactory.create())
                        .flatMapMany(connection -> {
                            var statement = connection.createStatement(DELETE_BY_QUEUE_IDS)
                                    .bind("project_id", entry.getKey())
                                    .bind("ids", entry.getValue().toArray(UUID[]::new));

                            return makeMonoContextAware(bindWorkspaceIdToMono(statement));
                        })
                        .flatMap(Result::getRowsUpdated))
                .reduce(0L, Long::sum);
    }
}
