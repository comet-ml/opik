package com.comet.opik.api.resources.v1.events;

import com.comet.opik.domain.DatasetExportMessage;
import com.comet.opik.infrastructure.DatasetExportConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.util.UUID;

/**
 * Unit tests for DatasetExportJobListener.
 *
 * Note: This listener currently just logs the message and returns empty Mono.
 * Full CSV generation logic will be implemented in PR#5.
 */
@ExtendWith(MockitoExtension.class)
class DatasetExportJobListenerTest {

    @Mock
    private RedissonReactiveClient redisClient;

    @Mock
    private DatasetExportConfig config;

    private DatasetExportJobListener listener;

    @BeforeEach
    void setUp() {
        listener = new DatasetExportJobListener(config, redisClient);
    }

    @Test
    void processEvent_shouldCompleteSuccessfully_whenMessageReceived() {
        // Given
        DatasetExportMessage message = DatasetExportMessage.builder()
                .jobId(UUID.randomUUID())
                .datasetId(UUID.randomUUID())
                .workspaceId("test-workspace")
                .build();

        // When
        Mono<Void> result = listener.processEvent(message);

        // Then
        StepVerifier.create(result)
                .verifyComplete();
    }
}
