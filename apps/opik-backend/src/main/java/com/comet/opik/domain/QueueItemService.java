package com.comet.opik.domain;

import com.comet.opik.api.Page;
import com.comet.opik.api.QueueItem;
import com.comet.opik.api.QueueItemsBatch;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceThread;
import com.comet.opik.infrastructure.db.TransactionTemplate;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class QueueItemService {

    private final @NonNull QueueItemDAO queueItemDAO;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull TraceService traceService;
    private final @NonNull TraceDAO traceDAO;

    public List<QueueItem> addItemsToQueue(UUID queueId, QueueItemsBatch batch) {
        log.info("Adding '{}' items to queue '{}'", batch.items().size(), queueId);

        return transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(QueueItemDAO.class);
            var now = Instant.now();

            var queueItems = batch.items().stream()
                    .map(item -> QueueItem.builder()
                            .id(UUID.fromString(idGenerator.generate()))
                            .queueId(queueId)
                            .itemType(item.itemType())
                            .itemId(item.itemId())
                            .status(QueueItem.QueueItemStatus.PENDING)
                            .createdAt(now)
                            .build())
                    .toList();

            repository.insertBatch(queueItems);
            log.info("Successfully added '{}' items to queue '{}'", queueItems.size(), queueId);
            return queueItems;
        });
    }

    public Page<QueueItem> getQueueItems(UUID queueId, int page, int size) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(QueueItemDAO.class);
            return repository.findByQueueIdPaginated(queueId, page, size);
        });
    }

    public Page<QueueItem> getQueueItemsWithData(UUID queueId, int page, int size, List<String> visibleFields) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(QueueItemDAO.class);
            var queueItems = repository.findByQueueIdPaginated(queueId, page, size);

            // Enrich with trace/thread data
            var enrichedItems = queueItems.content().stream()
                    .map(item -> enrichQueueItemWithData(item, visibleFields))
                    .toList();

            return queueItems.toBuilder()
                    .content(enrichedItems)
                    .build();
        });
    }

    public Optional<QueueItem> getNextItemForSme(UUID queueId, String smeId) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(QueueItemDAO.class);
            var nextItem = repository.getNextItemForSme(queueId, smeId);
            
            return nextItem.map(item -> {
                // Assign the item to the SME if not already assigned
                if (item.assignedSme() == null) {
                    assignItemToSme(item.id(), queueId, smeId);
                    return item.toBuilder().assignedSme(smeId).build();
                }
                return item;
            });
        });
    }

    public QueueItem getQueueItemWithData(UUID queueId, UUID itemId, List<String> visibleFields) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(QueueItemDAO.class);
            var queueItem = repository.findById(itemId, queueId)
                    .orElseThrow(() -> new NotFoundException("Queue item not found: '%s'".formatted(itemId)));

            return enrichQueueItemWithData(queueItem, visibleFields);
        });
    }

    public void updateItemStatus(UUID queueId, UUID itemId, QueueItem.QueueItemStatus status, String smeId) {
        log.info("Updating queue item '{}' status to '{}' for SME '{}'", itemId, status, smeId);

        transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(QueueItemDAO.class);
            var completedAt = status == QueueItem.QueueItemStatus.COMPLETED ? Instant.now() : null;
            
            int updated = repository.updateStatus(itemId, queueId, status.getValue(), smeId, completedAt);
            if (updated == 0) {
                throw new NotFoundException("Queue item not found: '%s'".formatted(itemId));
            }
            return null;
        });
    }

    public void assignItemToSme(UUID itemId, UUID queueId, String smeId) {
        log.info("Assigning queue item '{}' to SME '{}'", itemId, smeId);

        transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(QueueItemDAO.class);
            int updated = repository.updateStatus(itemId, queueId, 
                    QueueItem.QueueItemStatus.IN_PROGRESS.getValue(), smeId, null);
            if (updated == 0) {
                throw new NotFoundException("Queue item not found: '%s'".formatted(itemId));
            }
            return null;
        });
    }

    public void removeItemFromQueue(UUID queueId, UUID itemId) {
        log.info("Removing item '{}' from queue '{}'", itemId, queueId);

        transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(QueueItemDAO.class);
            int deleted = repository.delete(itemId, queueId);
            if (deleted == 0) {
                throw new NotFoundException("Queue item not found: '%s'".formatted(itemId));
            }
            return null;
        });
    }

    public void removeItemByReference(UUID queueId, QueueItem.QueueItemType itemType, String itemId) {
        log.info("Removing item '{}' of type '{}' from queue '{}'", itemId, itemType, queueId);

        transactionTemplate.inTransaction(WRITE, handle -> {
            var repository = handle.attach(QueueItemDAO.class);
            repository.deleteByItem(queueId, itemType.getValue(), itemId);
            return null;
        });
    }

    private QueueItem enrichQueueItemWithData(QueueItem queueItem, List<String> visibleFields) {
        try {
            if (queueItem.itemType() == QueueItem.QueueItemType.TRACE) {
                var trace = getTraceData(queueItem.itemId(), visibleFields);
                return queueItem.toBuilder().traceData(trace).build();
            } else if (queueItem.itemType() == QueueItem.QueueItemType.THREAD) {
                var thread = getThreadData(queueItem.itemId(), visibleFields);
                return queueItem.toBuilder().threadData(thread).build();
            }
        } catch (Exception e) {
            log.warn("Failed to enrich queue item '{}' with data", queueItem.id(), e);
        }
        return queueItem;
    }

    private Trace getTraceData(String traceId, List<String> visibleFields) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(TraceDAO.class);
            return repository.findById(UUID.fromString(traceId))
                    .orElse(null);
        });
    }

    private TraceThread getThreadData(String threadId, List<String> visibleFields) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var repository = handle.attach(TraceDAO.class);
            // TODO: Implement thread data retrieval
            // This would need to be implemented based on the existing thread structure
            return null;
        });
    }
}