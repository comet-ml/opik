package com.comet.opik.domain;

import com.comet.opik.api.Annotation;
import com.comet.opik.api.resources.v1.pub.PublicAnnotationQueuesResource.AnnotationCreate;
import com.comet.opik.infrastructure.db.TransactionTemplate;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class AnnotationService {

    private final @NonNull AnnotationDAO annotationDAO;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;

    public Annotation createAnnotation(UUID queueItemId, String smeId, AnnotationCreate request) {
        log.info("Creating annotation for queue item '{}' by SME '{}'", queueItemId, smeId);

        return transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(AnnotationDAO.class);
            var now = Instant.now();

            var annotation = Annotation.builder()
                    .id(UUID.fromString(idGenerator.generate()))
                    .queueItemId(queueItemId)
                    .smeId(smeId)
                    .metrics(request.metrics())
                    .comment(request.comment())
                    .createdAt(now)
                    .updatedAt(now)
                    .build();

            return repository.create(annotation);
        });
    }

    public Annotation updateAnnotation(UUID annotationId, AnnotationCreate request) {
        log.info("Updating annotation '{}'", annotationId);

        return transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(AnnotationDAO.class);
            var now = Instant.now();

            var annotation = Annotation.builder()
                    .id(annotationId)
                    .metrics(request.metrics())
                    .comment(request.comment())
                    .updatedAt(now)
                    .build();

            return repository.update(annotation);
        });
    }

    public List<Annotation> getAnnotationsByQueueItem(UUID queueItemId) {
        log.info("Getting annotations for queue item '{}'", queueItemId);

        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(AnnotationDAO.class);
            return repository.findByQueueItemId(queueItemId);
        });
    }

    public List<Annotation> getAnnotationsBySme(String smeId, int page, int size) {
        log.info("Getting annotations for SME '{}', page '{}', size '{}'", smeId, page, size);

        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(AnnotationDAO.class);
            var offset = (page - 1) * size;
            return repository.findBySmeId(smeId, size, offset);
        });
    }

    public void deleteAnnotation(UUID annotationId) {
        log.info("Deleting annotation '{}'", annotationId);

        transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(AnnotationDAO.class);
            repository.delete(annotationId);
            return null;
        });
    }
}