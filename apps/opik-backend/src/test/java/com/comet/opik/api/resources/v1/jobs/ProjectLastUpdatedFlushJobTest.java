package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.ProjectLastUpdatedTraceBufferService;
import com.comet.opik.infrastructure.ProjectLastUpdatedFlushConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.quartz.JobExecutionContext;
import reactor.core.publisher.Mono;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectLastUpdatedFlushJob Tests")
class ProjectLastUpdatedFlushJobTest {

    @Mock
    private ProjectLastUpdatedTraceBufferService bufferService;
    @Mock
    private LockService lockService;
    @Mock
    private JobExecutionContext jobContext;

    private ProjectLastUpdatedFlushJob newJob(boolean enabled) {
        return new ProjectLastUpdatedFlushJob(buildConfig(enabled), bufferService, lockService);
    }

    @Test
    @DisplayName("When disabled, skips locking and flushing entirely")
    void doJob__whenDisabled__skips() {
        newJob(false).doJob(jobContext);

        verify(lockService, never()).bestEffortLock(any(), any(), any(), any(), any(), anyBoolean());
        verify(bufferService, never()).flush();
    }

    @Test
    @DisplayName("When the lock cannot be acquired, does not flush")
    void doJob__whenLockNotAcquired__doesNotFlush() {
        when(lockService.bestEffortLock(any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> invocation.<Mono<Void>>getArgument(2));

        newJob(true).doJob(jobContext);

        verify(bufferService, never()).flush();
    }

    @Test
    @DisplayName("When the lock is acquired, delegates to the buffer service flush")
    void doJob__whenLockAcquired__flushes() {
        when(bufferService.flush()).thenReturn(3L);
        when(lockService.bestEffortLock(any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> invocation.<Mono<Void>>getArgument(1));

        newJob(true).doJob(jobContext);

        // flush() runs on boundedElastic (fire-and-forget subscribe), so await it.
        verify(bufferService, timeout(1_000)).flush();
    }

    private static ProjectLastUpdatedFlushConfig buildConfig(boolean enabled) {
        var config = new ProjectLastUpdatedFlushConfig();
        config.setEnabled(enabled);
        config.setJobInterval(Duration.seconds(30));
        config.setJobLockTime(Duration.seconds(25));
        config.setJobLockWaitTime(Duration.milliseconds(500));
        config.setJobBatchSize(500);
        return config;
    }
}
