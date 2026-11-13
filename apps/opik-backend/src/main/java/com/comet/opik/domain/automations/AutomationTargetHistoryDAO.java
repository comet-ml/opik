package com.comet.opik.domain.automations;

import com.comet.opik.utils.template.TemplateUtils;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContext;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;

/**
 * Every item an automation ever wrote to a test suite or dataset, the counterpart of
 * {@code AnnotationQueueItemHistoryDAO} for the other write targets. Existence is the only question it
 * answers for the executor; the counts serve the recommendations.
 */
@ImplementedBy(AutomationTargetHistoryDAOImpl.class)
public interface AutomationTargetHistoryDAO {

    enum TargetType {
        TEST_SUITE("test_suite"),
        DATASET("dataset");

        private final String value;

        TargetType(String value) {
            this.value = value;
        }

        public String getValue() {
            return value;
        }
    }

    Mono<Long> recordItems(TargetType targetType, UUID targetId, UUID automationId, Set<UUID> itemIds,
            UUID projectId);

    Mono<Set<UUID>> findPreviouslyAddedItems(TargetType targetType, UUID targetId, UUID projectId,
            Set<UUID> itemIds);

    /** How many items automation wrote to any target of the project since an instant, by target. */
    Mono<Long> countWrittenSince(UUID projectId, Instant since);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class AutomationTargetHistoryDAOImpl implements AutomationTargetHistoryDAO {

    private static final String BATCH_INSERT = """
            INSERT INTO automation_target_history (
                target_type, target_id, item_id, automation_id, project_id, workspace_id, created_by, last_updated_by
            )
            VALUES
                <items:{item |
                    (
                        :target_type,
                        :target_id,
                        :item_id<item.index>,
                        :automation_id,
                        :project_id,
                        :workspace_id,
                        :user_name,
                        :user_name
                    )
                    <if(item.hasNext)>,<endif>
                }>
            """;

    private static final String SELECT_PREVIOUSLY_ADDED = """
            SELECT DISTINCT item_id
            FROM automation_target_history
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND target_type = :target_type
            AND target_id = :target_id
            AND item_id IN :item_ids
            """;

    private static final String COUNT_WRITTEN_SINCE = """
            SELECT count(DISTINCT target_type, target_id, item_id) AS count
            FROM automation_target_history
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND created_at >= parseDateTime64BestEffort(:since, 9)
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    @Override
    public Mono<Long> recordItems(@NonNull TargetType targetType, @NonNull UUID targetId, @NonNull UUID automationId,
            @NonNull Set<UUID> itemIds, @NonNull UUID projectId) {
        if (itemIds.isEmpty()) {
            return Mono.just(0L);
        }

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var template = TemplateUtils.newST(BATCH_INSERT)
                            .add("items", getQueryItemPlaceHolder(itemIds.size()));

                    Statement statement = connection.createStatement(template.render())
                            .bind("target_type", targetType.getValue())
                            .bind("target_id", targetId.toString())
                            .bind("automation_id", automationId.toString())
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
    public Mono<Set<UUID>> findPreviouslyAddedItems(@NonNull TargetType targetType, @NonNull UUID targetId,
            @NonNull UUID projectId, @NonNull Set<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            return Mono.just(Set.of());
        }

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(SELECT_PREVIOUSLY_ADDED)
                            .bind("project_id", projectId.toString())
                            .bind("target_type", targetType.getValue())
                            .bind("target_id", targetId.toString())
                            .bind("item_ids", itemIds.toArray(UUID[]::new));

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map(
                        (row, metadata) -> UUID.fromString(row.get("item_id", String.class))))
                .collect(Collectors.toSet());
    }

    @Override
    public Mono<Long> countWrittenSince(@NonNull UUID projectId, @NonNull Instant since) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(COUNT_WRITTEN_SINCE)
                            .bind("project_id", projectId.toString())
                            .bind("since", since.toString());

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
                })
                .flatMap(result -> result.map((row, metadata) -> row.get("count", Long.class)))
                .reduce(0L, Long::sum);
    }
}
