package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.RetryUtils;
import com.google.common.collect.Lists;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * Deletes the topics of deleted projects, with their prototype vectors: for the project-delete listener and the daily
 * sweep. Each delete is {@code topics.storage.deleteBatchSize} projects per transaction, retried on deadlock. MySQL
 * calls are blocking JDBI, each wrapped once on a bounded-elastic thread.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicDataCleanupService {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TransactionTemplate template;

    /** Deletes the topics of the given projects. The workspace comes from the Reactor context. */
    public Mono<Integer> deleteTopicsOfProjects(@NonNull Collection<UUID> projectIds) {
        return Mono.deferContextual(ctx -> deleteTopics(ctx.get(RequestContext.WORKSPACE_ID), projectIds));
    }

    /**
     * Deletes the topics of the workspaces' projects that no longer exist: those a project delete left, because its
     * listener failed or a run saved after it. A plain read finds them, then each workspace's are deleted by key.
     * Returns how many topics it deleted.
     */
    public Mono<Integer> deleteTopicsOfMissingProjects(@NonNull List<String> workspaceIds) {
        if (workspaceIds.isEmpty()) {
            return Mono.just(0);
        }
        return Mono.fromCallable(() -> template.inTransaction(READ_ONLY,
                handle -> handle.attach(TopicDAO.class).findTopicProjectsOfMissingProjects(workspaceIds)))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMapMany(missing -> Flux.fromIterable(missing.stream()
                        .collect(Collectors.groupingBy(TopicProjectKey::workspaceId,
                                Collectors.mapping(TopicProjectKey::projectId, Collectors.toList())))
                        .entrySet()))
                .concatMap(workspace -> deleteTopics(workspace.getKey(), workspace.getValue()))
                .reduce(0, Integer::sum);
    }

    private Mono<Integer> deleteTopics(String workspaceId, Collection<UUID> projectIds) {
        return Flux.fromIterable(Lists.partition(List.copyOf(projectIds), config.getStorage().getDeleteBatchSize()))
                .concatMap(batch -> Mono.fromCallable(() -> template.inTransaction(WRITE,
                        handle -> handle.attach(TopicDAO.class).deleteTopicsByProjects(workspaceId, batch)))
                        .subscribeOn(Schedulers.boundedElastic())
                        .retryWhen(RetryUtils.handleOnDeadLocks()))
                .reduce(0, Integer::sum);
    }
}
