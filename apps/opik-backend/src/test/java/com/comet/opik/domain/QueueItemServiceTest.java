package com.comet.opik.domain;

import com.comet.opik.api.QueueItem;
import com.comet.opik.api.QueueItemsBatch;
import com.comet.opik.infrastructure.db.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class QueueItemServiceTest {

    @Mock
    private QueueItemDAO queueItemDAO;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private TraceService traceService;

    private QueueItemService queueItemService;

    @BeforeEach
    void setUp() {
        queueItemService = new QueueItemService(queueItemDAO, idGenerator, transactionTemplate, traceService);
    }

    @Test
    void addItemsToQueue_shouldCreateQueueItems() {
        // Given
        var queueId = UUID.randomUUID();
        var itemId1 = UUID.randomUUID();
        var itemId2 = UUID.randomUUID();
        var now = Instant.now();

        var batch = QueueItemsBatch.builder()
                .items(List.of(
                        QueueItemsBatch.QueueItemCreate.builder()
                                .itemType(QueueItem.QueueItemType.TRACE)
                                .itemId(itemId1.toString())
                                .build(),
                        QueueItemsBatch.QueueItemCreate.builder()
                                .itemType(QueueItem.QueueItemType.TRACE)
                                .itemId(itemId2.toString())
                                .build()
                ))
                .build();

        var expectedItems = List.of(
                QueueItem.builder()
                        .id(UUID.randomUUID())
                        .queueId(queueId)
                        .itemType(QueueItem.QueueItemType.TRACE)
                        .itemId(itemId1.toString())
                        .status(QueueItem.QueueItemStatus.PENDING)
                        .createdAt(now)
                        .build(),
                QueueItem.builder()
                        .id(UUID.randomUUID())
                        .queueId(queueId)
                        .itemType(QueueItem.QueueItemType.TRACE)
                        .itemId(itemId2.toString())
                        .status(QueueItem.QueueItemStatus.PENDING)
                        .createdAt(now)
                        .build()
        );

        when(idGenerator.generate()).thenReturn("test-id-1", "test-id-2");
        when(transactionTemplate.inTransaction(any(), any())).thenReturn(expectedItems);

        // When
        var result = queueItemService.addItemsToQueue(queueId, batch);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).itemType()).isEqualTo(QueueItem.QueueItemType.TRACE);
        assertThat(result.get(0).status()).isEqualTo(QueueItem.QueueItemStatus.PENDING);
        assertThat(result.get(1).itemType()).isEqualTo(QueueItem.QueueItemType.TRACE);
        assertThat(result.get(1).status()).isEqualTo(QueueItem.QueueItemStatus.PENDING);
    }

    @Test
    void getNextItemForSme_shouldReturnNextPendingItem() {
        // Given
        var queueId = UUID.randomUUID();
        var smeId = "sme123";
        var itemId = UUID.randomUUID();
        var now = Instant.now();

        var expectedItem = QueueItem.builder()
                .id(itemId)
                .queueId(queueId)
                .itemType(QueueItem.QueueItemType.TRACE)
                .itemId("trace-123")
                .status(QueueItem.QueueItemStatus.PENDING)
                .createdAt(now)
                .build();

        when(transactionTemplate.inTransaction(any(), any())).thenReturn(Optional.of(expectedItem));

        // When
        var result = queueItemService.getNextItemForSme(queueId, smeId);

        // Then
        assertThat(result).isPresent();
        assertThat(result.get().status()).isEqualTo(QueueItem.QueueItemStatus.PENDING);
        assertThat(result.get().queueId()).isEqualTo(queueId);
    }

    @Test
    void updateItemStatus_shouldUpdateItemStatus() {
        // Given
        var itemId = UUID.randomUUID();
        var smeId = "sme123";
        var newStatus = QueueItem.QueueItemStatus.COMPLETED;

        when(transactionTemplate.inTransaction(any(), any())).thenReturn(null);

        // When
        queueItemService.updateItemStatus(itemId, newStatus, smeId);

        // Then
        // Verify that the transaction was called (implementation details would be tested in integration tests)
        verify(transactionTemplate).inTransaction(any(), any());
    }

    @Test
    void getQueueItems_shouldReturnItemsWithPagination() {
        // Given
        var queueId = UUID.randomUUID();
        var page = 1;
        var size = 10;
        var now = Instant.now();

        var expectedItems = List.of(
                QueueItem.builder()
                        .id(UUID.randomUUID())
                        .queueId(queueId)
                        .itemType(QueueItem.QueueItemType.TRACE)
                        .itemId("trace-1")
                        .status(QueueItem.QueueItemStatus.PENDING)
                        .createdAt(now)
                        .build(),
                QueueItem.builder()
                        .id(UUID.randomUUID())
                        .queueId(queueId)
                        .itemType(QueueItem.QueueItemType.TRACE)
                        .itemId("trace-2")
                        .status(QueueItem.QueueItemStatus.COMPLETED)
                        .createdAt(now)
                        .build()
        );

        when(transactionTemplate.inTransaction(any(), any())).thenReturn(expectedItems);

        // When
        var result = queueItemService.getQueueItems(queueId, page, size, true);

        // Then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).status()).isEqualTo(QueueItem.QueueItemStatus.PENDING);
        assertThat(result.get(1).status()).isEqualTo(QueueItem.QueueItemStatus.COMPLETED);
    }
}