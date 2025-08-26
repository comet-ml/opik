package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueCreate;
import com.comet.opik.api.Page;
import com.comet.opik.infrastructure.db.TransactionTemplate;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class AnnotationQueueService {

    private final @NonNull AnnotationQueueDAO annotationQueueDAO;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;

    public AnnotationQueue createQueue(AnnotationQueueCreate request, UUID projectId, String createdBy) {
        log.info("Creating annotation queue '{}' for project '{}'", request.name(), projectId);

        return transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(AnnotationQueueDAO.class);
            var now = Instant.now();

            var queue = AnnotationQueue.builder()
                    .id(UUID.fromString(idGenerator.generate()))
                    .name(request.name())
                    .description(request.description())
                    .status(AnnotationQueue.AnnotationQueueStatus.ACTIVE)
                    .createdBy(createdBy)
                    .projectId(projectId)
                    .templateId(request.templateId())
                    .visibleFields(request.visibleFields() != null ? request.visibleFields() : 
                                 List.of("input", "output", "timestamp"))
                    .requiredMetrics(request.requiredMetrics() != null ? request.requiredMetrics() : 
                                   List.of("rating"))
                    .optionalMetrics(request.optionalMetrics() != null ? request.optionalMetrics() : 
                                   List.of("comment"))
                    .instructions(request.instructions())
                    .dueDate(request.dueDate())
                    .createdAt(now)
                    .updatedAt(now)
                    .totalItems(0)
                    .completedItems(0)
                    .assignedSmes(List.of())
                    .build();

            repository.insert(queue);
            log.info("Successfully created annotation queue with id '{}'", queue.id());
            return queue;
        });
    }

    public AnnotationQueue getQueue(UUID queueId, UUID projectId) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(AnnotationQueueDAO.class);
            return repository.findById(queueId, projectId)
                    .orElseThrow(() -> new NotFoundException("Annotation queue not found: '%s'".formatted(queueId)));
        });
    }

    public AnnotationQueue getQueuePublic(UUID queueId) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(AnnotationQueueDAO.class);
            return repository.findByIdPublic(queueId)
                    .orElseThrow(() -> new NotFoundException("Annotation queue not found: '%s'".formatted(queueId)));
        });
    }

    public Page<AnnotationQueue> getQueues(UUID projectId, int page, int size) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(AnnotationQueueDAO.class);
            return repository.findByProjectIdPaginated(projectId, page, size);
        });
    }

    public AnnotationQueue updateQueue(UUID queueId, UUID projectId, AnnotationQueueCreate request) {
        log.info("Updating annotation queue '{}' for project '{}'", queueId, projectId);

        return transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(AnnotationQueueDAO.class);

            // First, get the existing queue
            var existingQueue = repository.findById(queueId, projectId)
                    .orElseThrow(() -> new NotFoundException("Annotation queue not found: '%s'".formatted(queueId)));

            // Build updated queue
            var updatedQueue = existingQueue.toBuilder()
                    .name(request.name())
                    .description(request.description())
                    .templateId(request.templateId())
                    .visibleFields(request.visibleFields() != null ? request.visibleFields() : 
                                 existingQueue.visibleFields())
                    .requiredMetrics(request.requiredMetrics() != null ? request.requiredMetrics() : 
                                   existingQueue.requiredMetrics())
                    .optionalMetrics(request.optionalMetrics() != null ? request.optionalMetrics() : 
                                   existingQueue.optionalMetrics())
                    .instructions(request.instructions())
                    .dueDate(request.dueDate())
                    .updatedAt(Instant.now())
                    .build();

            int updated = repository.update(updatedQueue, projectId);
            if (updated == 0) {
                throw new NotFoundException("Annotation queue not found: '%s'".formatted(queueId));
            }

            log.info("Successfully updated annotation queue '{}'", queueId);
            return updatedQueue;
        });
    }

    public void updateQueueStatus(UUID queueId, UUID projectId, AnnotationQueue.AnnotationQueueStatus status) {
        log.info("Updating annotation queue '{}' status to '{}'", queueId, status);

        transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(AnnotationQueueDAO.class);
            int updated = repository.updateStatus(queueId, projectId, status.getValue(), Instant.now());
            if (updated == 0) {
                throw new NotFoundException("Annotation queue not found: '%s'".formatted(queueId));
            }
            return null;
        });
    }

    public void deleteQueue(UUID queueId, UUID projectId) {
        log.info("Deleting annotation queue '{}' for project '{}'", queueId, projectId);

        transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(AnnotationQueueDAO.class);
            int deleted = repository.delete(queueId, projectId);
            if (deleted == 0) {
                throw new NotFoundException("Annotation queue not found: '%s'".formatted(queueId));
            }
            log.info("Successfully deleted annotation queue '{}'", queueId);
            return null;
        });
    }

    public String generateShareUrl(UUID queueId) {
        // TODO: This should be configurable based on environment
        return "https://app.opik.ml/annotation/" + queueId;
    }
}