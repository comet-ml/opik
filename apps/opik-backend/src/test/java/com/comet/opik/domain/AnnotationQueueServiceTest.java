package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueCreate;
import com.comet.opik.infrastructure.db.TransactionTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AnnotationQueueServiceTest {

    @Mock
    private AnnotationQueueDAO annotationQueueDAO;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private TransactionTemplate transactionTemplate;

    private AnnotationQueueService annotationQueueService;

    @BeforeEach
    void setUp() {
        annotationQueueService = new AnnotationQueueService(annotationQueueDAO, idGenerator, transactionTemplate);
    }

    @Test
    void createQueue_shouldCreateAnnotationQueue() {
        // Given
        var projectId = UUID.randomUUID();
        var createdBy = "user123";
        var queueId = UUID.randomUUID();
        var now = Instant.now();

        var request = AnnotationQueueCreate.builder()
                .name("Test Queue")
                .description("Test Description")
                .instructions("Test Instructions")
                .build();

        var expectedQueue = AnnotationQueue.builder()
                .id(queueId)
                .name("Test Queue")
                .description("Test Description")
                .status(AnnotationQueue.AnnotationQueueStatus.ACTIVE)
                .createdBy(createdBy)
                .projectId(projectId)
                .visibleFields(List.of("input", "output", "timestamp"))
                .requiredMetrics(List.of("rating"))
                .optionalMetrics(List.of("comment"))
                .instructions("Test Instructions")
                .createdAt(now)
                .updatedAt(now)
                .totalItems(0)
                .completedItems(0)
                .assignedSmes(List.of())
                .build();

        when(idGenerator.generate()).thenReturn(queueId.toString());
        when(transactionTemplate.inTransaction(any(), any())).thenReturn(expectedQueue);

        // When
        var result = annotationQueueService.createQueue(request, projectId, createdBy);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("Test Queue");
        assertThat(result.description()).isEqualTo("Test Description");
        assertThat(result.status()).isEqualTo(AnnotationQueue.AnnotationQueueStatus.ACTIVE);
        assertThat(result.createdBy()).isEqualTo(createdBy);
        assertThat(result.projectId()).isEqualTo(projectId);
    }

    @Test
    void generateShareUrl_shouldReturnCorrectUrl() {
        // Given
        var queueId = UUID.randomUUID();

        // When
        var shareUrl = annotationQueueService.generateShareUrl(queueId);

        // Then
        assertThat(shareUrl).isEqualTo("https://app.opik.ml/annotation/" + queueId);
    }
}