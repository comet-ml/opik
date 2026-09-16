package com.comet.opik.api.resources.v1.events;

import com.comet.opik.domain.CsvExportProcessor;
import com.comet.opik.domain.ExportJobService;
import com.comet.opik.infrastructure.ExportConfig;
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
 * Unit tests for ExportJobSubscriber lifecycle gating.
 * Tests verify that the subscriber respects the enabled/disabled configuration.
 */
@ExtendWith(MockitoExtension.class)
class ExportJobSubscriberTest {

    @Mock
    private ExportConfig config;

    @Mock
    private RedissonReactiveClient redisClient;

    @Mock
    private ExportJobService jobService;

    @Mock
    private CsvExportProcessor csvProcessor;

    private ExportJobSubscriber subscriber;

    @Test
    void start_shouldSkipStartup_whenDisabled() {
        // Given
        when(config.isAnyEnabled()).thenReturn(false);
        subscriber = spy(new ExportJobSubscriber(config, redisClient, jobService, csvProcessor));

        // When
        subscriber.start();

        // Then - verify superclass start() was never called by checking no interaction with redis client
        verify(redisClient, never()).getStream(any(), any());
    }

    @Test
    void stop_shouldSkipShutdown_whenDisabled() {
        // Given
        when(config.isAnyEnabled()).thenReturn(false);
        subscriber = spy(new ExportJobSubscriber(config, redisClient, jobService, csvProcessor));

        // When
        subscriber.stop();

        // Then - verify no shutdown interactions occurred
        verify(redisClient, never()).getStream(any(), any());
    }
}
