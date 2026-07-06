package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.ProjectLastUpdatedFlushConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.BeforeEach;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ProjectLastUpdatedFlushJob Tests")
class ProjectLastUpdatedFlushJobTest {

    @Mock
    private ProjectService projectService;
    @Mock
    private LockService lockService;
    @Mock
    private JobExecutionContext jobContext;

    private ProjectLastUpdatedFlushConfig config;
    private ProjectLastUpdatedFlushJob job;

    @BeforeEach
    void setUp() {
        config = buildConfig(true);
        job = new ProjectLastUpdatedFlushJob(config, projectService, lockService);
    }

    @Test
    @DisplayName("When disabled, skips locking and flushing entirely")
    void doJob__whenDisabled__skips() {
        job = new ProjectLastUpdatedFlushJob(buildConfig(false), projectService, lockService);

        job.doJob(jobContext);

        verify(lockService, never()).bestEffortLock(any(), any(), any(), any(), any(), anyBoolean());
        verify(projectService, never()).flushLastUpdatedTraces();
    }

    @Test
    @DisplayName("When the lock cannot be acquired, does not flush")
    void doJob__whenLockNotAcquired__doesNotFlush() {
        // Return the fail-to-acquire action (arg 2), simulating a lost lock race.
        when(lockService.bestEffortLock(any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> invocation.<Mono<Void>>getArgument(2));

        job.doJob(jobContext);

        verify(projectService, never()).flushLastUpdatedTraces();
    }

    @Test
    @DisplayName("When the lock is acquired, runs the flush")
    void doJob__whenLockAcquired__flushes() {
        when(projectService.flushLastUpdatedTraces()).thenReturn(Mono.just(3L));
        // Run the guarded action (arg 1).
        when(lockService.bestEffortLock(any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> invocation.<Mono<Void>>getArgument(1));

        job.doJob(jobContext);

        verify(projectService).flushLastUpdatedTraces();
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
