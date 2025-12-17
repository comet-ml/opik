package com.comet.opik.domain;

import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.sorting.TraceThreadSortingFactory;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static com.comet.opik.utils.ErrorUtils.failWithNotFound;

@ImplementedBy(ThreadServiceImpl.class)
public interface ThreadService {

    Mono<TraceThread.TraceThreadPage> find(int page, int size, TraceSearchCriteria criteria);

    Mono<TraceThread> getById(UUID projectId, String threadId, boolean truncate);

    Flux<TraceThread> search(int limit, @NonNull TraceSearchCriteria criteria);

    Mono<ProjectStats> getStats(TraceSearchCriteria searchCriteria);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ThreadServiceImpl implements ThreadService {

    private final @NonNull ThreadDAO dao;
    private final @NonNull ProjectService projectService;
    private final @NonNull TraceThreadSortingFactory traceThreadSortingFactory;

    @Override
    public Mono<TraceThread.TraceThreadPage> find(int page, int size, @NonNull TraceSearchCriteria criteria) {
        return findProjectAndVerifyVisibility(criteria)
                .flatMap(it -> dao.find(size, page, it))
                .switchIfEmpty(Mono
                        .just(TraceThread.TraceThreadPage.empty(page, traceThreadSortingFactory.getSortableFields())));
    }

    @Override
    public Mono<TraceThread> getById(@NonNull UUID projectId, @NonNull String threadId, boolean truncate) {
        return dao.findById(projectId, threadId, truncate)
                .switchIfEmpty(Mono.defer(() -> Mono.error(failWithNotFound("Trace Thread", threadId))));
    }

    @Override
    public Flux<TraceThread> search(int limit, @NonNull TraceSearchCriteria criteria) {
        return findProjectAndVerifyVisibility(criteria)
                .flatMapMany(it -> dao.search(limit, it));
    }

    @Override
    @WithSpan
    public Mono<ProjectStats> getStats(@NonNull TraceSearchCriteria criteria) {
        return findProjectAndVerifyVisibility(criteria)
                .flatMap(dao::getThreadStats)
                .switchIfEmpty(Mono.just(ProjectStats.empty()));
    }

    private Mono<TraceSearchCriteria> findProjectAndVerifyVisibility(TraceSearchCriteria criteria) {
        return projectService.resolveProjectIdAndVerifyVisibility(criteria.projectId(), criteria.projectName())
                .map(projectId -> criteria.toBuilder()
                        .projectId(projectId)
                        .build());
    }
}
