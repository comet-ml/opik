package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.Trace;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.domain.evaluators.TraceFilterEvaluationService;
import com.comet.opik.domain.evaluators.TraceThreadFilterEvaluationService;
import com.comet.opik.domain.threads.TraceThreadModel;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.Set;

/**
 * Service for managing dynamic annotation queues.
 * Evaluates traces and threads against queue filter criteria and automatically adds matching items to queues.
 */
@ImplementedBy(DynamicAnnotationQueueServiceImpl.class)
public interface DynamicAnnotationQueueService {

    /**
     * Evaluates a trace against all dynamic queues for its project and adds it to matching queues.
     *
     * @param trace the trace to evaluate
     * @return Mono completing when evaluation and queue updates are done
     */
    Mono<Void> evaluateAndAddTrace(Trace trace);

    /**
     * Evaluates a batch of traces against all dynamic queues for their projects.
     *
     * @param traces the traces to evaluate
     * @return Mono completing when all evaluations and queue updates are done
     */
    Mono<Void> evaluateAndAddTraces(List<Trace> traces);

    /**
     * Evaluates a thread against all dynamic queues for its project and adds it to matching queues.
     *
     * @param thread the thread to evaluate
     * @return Mono completing when evaluation and queue updates are done
     */
    Mono<Void> evaluateAndAddThread(TraceThreadModel thread);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class DynamicAnnotationQueueServiceImpl implements DynamicAnnotationQueueService {

    private final @NonNull AnnotationQueueDAO annotationQueueDAO;
    private final @NonNull TraceFilterEvaluationService traceFilterEvaluationService;
    private final @NonNull TraceThreadFilterEvaluationService traceThreadFilterEvaluationService;

    @Override
    @WithSpan
    public Mono<Void> evaluateAndAddTrace(@NonNull Trace trace) {
        if (trace.projectId() == null) {
            log.warn("Cannot evaluate trace '{}' for dynamic queues: projectId is null", trace.id());
            return Mono.empty();
        }

        return annotationQueueDAO.findDynamicQueuesByProjectId(trace.projectId())
                .filter(queue -> queue.scope() == AnnotationQueue.AnnotationScope.TRACE)
                .filter(queue -> matchesTraceFilters(queue, trace))
                .flatMap(queue -> addTraceToQueue(queue, trace))
                .then()
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    @WithSpan
    public Mono<Void> evaluateAndAddTraces(@NonNull List<Trace> traces) {
        if (traces.isEmpty()) {
            return Mono.empty();
        }

        return Flux.fromIterable(traces)
                .flatMap(this::evaluateAndAddTrace)
                .then();
    }

    @Override
    @WithSpan
    public Mono<Void> evaluateAndAddThread(@NonNull TraceThreadModel thread) {
        if (thread.projectId() == null) {
            log.warn("Cannot evaluate thread '{}' for dynamic queues: projectId is null", thread.id());
            return Mono.empty();
        }

        return annotationQueueDAO.findDynamicQueuesByProjectId(thread.projectId())
                .filter(queue -> queue.scope() == AnnotationQueue.AnnotationScope.THREAD)
                .filter(queue -> matchesThreadFilters(queue, thread))
                .flatMap(queue -> addThreadToQueue(queue, thread))
                .then()
                .subscribeOn(Schedulers.boundedElastic());
    }

    private boolean matchesTraceFilters(AnnotationQueue queue, Trace trace) {
        JsonNode filterCriteria = queue.filterCriteria();
        if (filterCriteria == null || !filterCriteria.isArray() || filterCriteria.isEmpty()) {
            return true; // No filters means all traces match
        }

        try {
            String filterJson = JsonUtils.writeValueAsString(filterCriteria);
            List<TraceFilter> traceFilters = JsonUtils.readValue(filterJson, TraceFilter.LIST_TYPE_REFERENCE);
            boolean matches = traceFilterEvaluationService.matchesAllFilters(traceFilters, trace);

            if (matches) {
                log.debug("Trace '{}' matches dynamic queue '{}' criteria", trace.id(), queue.name());
            }

            return matches;
        } catch (Exception e) {
            log.error("Error parsing filter criteria for trace queue '{}': {}", queue.id(), e.getMessage(), e);
            return false;
        }
    }

    private boolean matchesThreadFilters(AnnotationQueue queue, TraceThreadModel thread) {
        JsonNode filterCriteria = queue.filterCriteria();
        if (filterCriteria == null || !filterCriteria.isArray() || filterCriteria.isEmpty()) {
            return true; // No filters means all threads match
        }

        try {
            List<TraceThreadFilter> threadFilters = JsonUtils.readValue(
                    JsonUtils.writeValueAsString(filterCriteria),
                    TraceThreadFilter.LIST_TYPE_REFERENCE);
            boolean matches = traceThreadFilterEvaluationService.matchesAllFilters(threadFilters, thread);

            if (matches) {
                log.debug("Thread '{}' matches dynamic queue '{}' criteria", thread.id(), queue.name());
            }

            return matches;
        } catch (Exception e) {
            log.error("Error parsing filter criteria for thread queue '{}': {}", queue.id(), e.getMessage(), e);
            return false;
        }
    }

    private Mono<Long> addTraceToQueue(AnnotationQueue queue, Trace trace) {
        log.info("Adding trace '{}' to dynamic queue '{}' (project: '{}')",
                trace.id(), queue.name(), queue.projectId());

        return annotationQueueDAO.addItems(queue.id(), Set.of(trace.id()), queue.projectId())
                .doOnSuccess(count -> {
                    if (count > 0) {
                        log.debug("Successfully added trace '{}' to dynamic queue '{}'", trace.id(), queue.name());
                    }
                })
                .doOnError(error -> log.error("Failed to add trace '{}' to dynamic queue '{}': {}",
                        trace.id(), queue.name(), error.getMessage()));
    }

    private Mono<Long> addThreadToQueue(AnnotationQueue queue, TraceThreadModel thread) {
        log.info("Adding thread '{}' to dynamic queue '{}' (project: '{}')",
                thread.id(), queue.name(), queue.projectId());

        return annotationQueueDAO.addItems(queue.id(), Set.of(thread.id()), queue.projectId())
                .doOnSuccess(count -> {
                    if (count > 0) {
                        log.debug("Successfully added thread '{}' to dynamic queue '{}'", thread.id(), queue.name());
                    }
                })
                .doOnError(error -> log.error("Failed to add thread '{}' to dynamic queue '{}': {}",
                        thread.id(), queue.name(), error.getMessage()));
    }
}
