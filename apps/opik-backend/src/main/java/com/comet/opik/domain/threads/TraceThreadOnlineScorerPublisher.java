package com.comet.opik.domain.threads;

import com.comet.opik.api.resources.v1.events.OnlineScoringQuotaService;
import com.comet.opik.domain.evaluators.OnlineScorePublisher;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TraceThreadOnlineScorerPublisher {

    private static final String THREAD_EVALUATOR_TYPE = "trace_thread";

    private final @NonNull OnlineScorePublisher onlineScorePublisher;
    private final @NonNull OnlineScoringQuotaService quotaService;

    public Mono<Void> publish(@NonNull UUID projectId, @NonNull List<TraceThreadModel> closeThreads) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            if (closeThreads.isEmpty()) {
                log.info("No threads to close for projectId '{}' in workspace {}", projectId, workspaceId);
                return Mono.empty();
            }

            Map<UUID, Set<String>> sampledThreadByRule = closeThreads
                    .stream()
                    .flatMap(closeThread -> closeThread.sampling().entrySet()
                            .stream()
                            .filter(this::isSampled)
                            .map(Map.Entry::getKey)
                            .map(ruleId -> Map.entry(ruleId, closeThread.threadId())))
                    .collect(Collectors.groupingBy(Map.Entry::getKey,
                            Collectors.mapping(Map.Entry::getValue, Collectors.toSet())));

            return Flux.fromIterable(sampledThreadByRule.entrySet())
                    .flatMap(ruleIdToThreadIds -> Mono.fromCallable(() -> {

                        UUID ruleId = ruleIdToThreadIds.getKey();
                        Set<String> threadIds = ruleIdToThreadIds.getValue();

                        var admittedThreadIds = quotaService.admit(workspaceId, ruleId, THREAD_EVALUATOR_TYPE,
                                List.copyOf(threadIds));
                        if (admittedThreadIds.isEmpty()) {
                            return ruleId;
                        }

                        log.info(
                                "Enqueuing threads: '{}' trace threads for ruleId: '{}' in projectId '{}' for workspaceId '{}'",
                                admittedThreadIds, ruleId, projectId, workspaceId);

                        onlineScorePublisher.enqueueThreadMessage(
                                admittedThreadIds,
                                ruleId,
                                projectId,
                                workspaceId,
                                userName);

                        log.info(
                                "Enqueued threads: '{}' trace threads for ruleId: '{}' in projectId '{}' for workspaceId '{}'",
                                admittedThreadIds, ruleId, projectId, workspaceId);

                        return ruleId;
                    }).subscribeOn(Schedulers.boundedElastic()))
                    .then();
        });
    }

    private boolean isSampled(Map.Entry<UUID, Boolean> entry) {
        // Check if the entry is sampled (true) or not (false)
        return entry.getValue();
    }

}
