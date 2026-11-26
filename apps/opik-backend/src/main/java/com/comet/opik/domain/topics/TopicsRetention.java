package com.comet.opik.domain.topics;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiFunction;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

/**
 * The Topics tables' part of a retention batch: the deletes of {@code topic_vectors} and then {@code trace_topics},
 * run only for the batch's workspaces that have Topics data. A workspace has rows in them only under a
 * {@code topic_projects} row: the vectors tick writes a project's row before it writes the project's first vector,
 * and a run or arrival labels only a project with a saved list. So one indexed MySQL read spares the two ClickHouse
 * deletes for every other workspace. When that read fails, the deletes run for the whole batch.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsRetention {

    private final @NonNull TransactionTemplate template;
    private final @NonNull TopicVectorsDAO vectorsDAO;
    private final @NonNull TraceTopicsDAO traceTopicsDAO;

    /**
     * Both deletes for those of {@code workspaceIds} with Topics data, in order; nothing when none has any.
     *
     * @param onError what a failed delete gives instead, by table
     */
    public Flux<Long> deleteForRetention(@NonNull List<String> workspaceIds, @NonNull UUID cutoffId,
            @NonNull UUID lowerBound, @NonNull BiFunction<String, Throwable, Mono<Long>> onError) {
        return withTopics(workspaceIds).flatMapMany(withTopics -> {
            var ids = workspaceIds.stream().filter(withTopics::contains).toList();
            if (ids.isEmpty()) {
                return Flux.empty();
            }
            return Flux.concat(
                    vectorsDAO.deleteForRetention(ids, cutoffId, lowerBound)
                            .onErrorResume(error -> onError.apply(TopicVectorsDAO.TABLE, error)),
                    traceTopicsDAO.deleteForRetention(ids, cutoffId, lowerBound)
                            .onErrorResume(error -> onError.apply(TraceTopicsDAO.TABLE, error)));
        });
    }

    /** {@link #deleteForRetention} with each workspace's own {@code trace_id} floor. */
    public Flux<Long> deleteForRetentionBounded(@NonNull Map<String, UUID> workspaceMinIds, @NonNull UUID cutoffId,
            @NonNull UUID lowerBound, @NonNull BiFunction<String, Throwable, Mono<Long>> onError) {
        return withTopics(workspaceMinIds.keySet()).flatMapMany(withTopics -> {
            var minIds = new LinkedHashMap<String, UUID>();
            workspaceMinIds.forEach((workspaceId, minId) -> {
                if (withTopics.contains(workspaceId)) {
                    minIds.put(workspaceId, minId);
                }
            });
            if (minIds.isEmpty()) {
                return Flux.empty();
            }
            return Flux.concat(
                    vectorsDAO.deleteForRetentionBounded(minIds, cutoffId, lowerBound)
                            .onErrorResume(error -> onError.apply(TopicVectorsDAO.TABLE, error)),
                    traceTopicsDAO.deleteForRetentionBounded(minIds, cutoffId, lowerBound)
                            .onErrorResume(error -> onError.apply(TraceTopicsDAO.TABLE, error)));
        });
    }

    /** The workspaces among {@code workspaceIds} with a {@code topic_projects} row; all of them when the read fails. */
    private Mono<Set<String>> withTopics(Collection<String> workspaceIds) {
        if (workspaceIds.isEmpty()) {
            return Mono.just(Set.of());
        }
        return Mono.fromCallable(() -> template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicProjectDAO.class).findWorkspacesWithProjects(workspaceIds)))
                .subscribeOn(Schedulers.boundedElastic())
                .<Set<String>>map(Set::copyOf)
                .onErrorResume(exception -> {
                    log.warn("Topics retention: the workspaces with Topics data were not read, running the Topics "
                            + "deletes for all '{}' workspaces of the batch", workspaceIds.size(), exception);
                    return Mono.just(Set.copyOf(workspaceIds));
                });
    }
}
