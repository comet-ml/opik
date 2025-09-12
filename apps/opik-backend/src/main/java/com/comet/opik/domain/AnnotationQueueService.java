package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@ImplementedBy(AnnotationQueueServiceImpl.class)
public interface AnnotationQueueService {

    Mono<UUID> create(@NonNull AnnotationQueue annotationQueue);
}

@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
@Slf4j
class AnnotationQueueServiceImpl implements AnnotationQueueService {

    private final @NonNull AnnotationQueueDAO annotationQueueDAO;
    private final @NonNull IdGenerator idGenerator;

    @Override
    @WithSpan
    public Mono<UUID> create(@NonNull AnnotationQueue annotationQueue) {
        UUID id = annotationQueue.id() == null ? idGenerator.generateId() : annotationQueue.id();
        IdGenerator.validateVersion(id, "AnnotationQueue");

        log.info("Creating annotation queue with id '{}', name '{}', project '{}'",
                id, annotationQueue.name(), annotationQueue.projectId());

        var newAnnotationQueue = annotationQueue.toBuilder()
                .id(id)
                .commentsEnabled(annotationQueue.commentsEnabled() != null ? annotationQueue.commentsEnabled() : true)
                .feedbackDefinitions(annotationQueue.feedbackDefinitions() != null
                        ? annotationQueue.feedbackDefinitions()
                        : List.of())
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .build();

        return annotationQueueDAO.create(newAnnotationQueue)
                .thenReturn(id)
                .subscribeOn(Schedulers.boundedElastic());
    }
}
