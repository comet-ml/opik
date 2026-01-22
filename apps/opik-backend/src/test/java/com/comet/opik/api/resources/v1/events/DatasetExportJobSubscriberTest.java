package com.comet.opik.api.resources.v1.events;

import com.comet.opik.domain.CsvDatasetExportProcessor;
import com.comet.opik.domain.DatasetExportJobService;
import com.comet.opik.infrastructure.DatasetExportConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RedissonReactiveClient;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for DatasetExportJobSubscriber lifecycle gating.
 * Tests verify that the subscriber respects the enabled/disabled configuration.
 */
@ExtendWith(MockitoExtension.class)
class DatasetExportJobSubscriberTest {

    @Mock
    private DatasetExportConfig config;

    @Mock
    private RedissonReactiveClient redisClient;

    @Mock
    private DatasetExportJobService jobService;

    @Mock
    private CsvDatasetExportProcessor csvProcessor;

    private DatasetExportJobSubscriber subscriber;

    @Test
    void start_shouldSkipStartup_whenDisabled() {
        // Given
        when(config.isEnabled()).thenReturn(false);
        subscriber = spy(new DatasetExportJobSubscriber(config, redisClient, jobService, csvProcessor));

        // When
        subscriber.start();

        // Then - verify superclass start() was never called by checking no interaction with redis client
        verify(redisClient, never()).getStream(any(), any());
    }

    @Test
    void stop_shouldSkipShutdown_whenDisabled() {
        // Given
        when(config.isEnabled()).thenReturn(false);
        subscriber = spy(new DatasetExportJobSubscriber(config, redisClient, jobService, csvProcessor));

        // When
        subscriber.stop();

        // Then - verify no shutdown interactions occurred
        verify(redisClient, never()).getStream(any(), any());
    }
}
