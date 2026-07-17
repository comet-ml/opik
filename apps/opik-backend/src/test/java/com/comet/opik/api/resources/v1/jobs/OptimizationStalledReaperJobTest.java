package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.OptimizationService;
import com.comet.opik.infrastructure.OptimizationStalledReaperConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.util.Duration;
import org.awaitility.Awaitility;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Optimization Stalled Reaper Job Test")
class OptimizationStalledReaperJobTest {

    private static final Duration INITIALIZED_TIMEOUT = Duration.minutes(5);
    private static final Duration RUNNING_TIMEOUT = Duration.hours(8);
    private static final int BATCH_SIZE = 42;

    @Mock
    private OptimizationService optimizationService;

    @Mock
    private LockService lockService;

    private OptimizationStalledReaperJob job;

    @BeforeEach
    void setUp() {
        var config = OptimizationStalledReaperConfig.builder()
                .enabled(true)
                .startupDelay(Duration.minutes(5))
                .jobInterval(Duration.minutes(5))
                .initializedTimeout(INITIALIZED_TIMEOUT)
                .runningTimeout(RUNNING_TIMEOUT)
                .lockDuration(Duration.minutes(4))
                .batchSize(BATCH_SIZE)
                .build();
        job = new OptimizationStalledReaperJob(optimizationService, lockService, config);
    }

    @Test
    @DisplayName("runs the reconcile pass with the configured timeouts and batch size under the lock")
    void runsReconcileUnderLock() {
        // Execute the guarded action (arg 1) so the reconcile call actually fires under the lock.
        when(lockService.bestEffortLock(any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> invocation.<Mono<Long>>getArgument(1));
        when(optimizationService.reconcileStalledStudioOptimizations(
                INITIALIZED_TIMEOUT.toJavaDuration(), RUNNING_TIMEOUT.toJavaDuration(), BATCH_SIZE))
                .thenReturn(Mono.just(3L));

        job.doJob(mock(JobExecutionContext.class));

        // doJob subscribes on boundedElastic (fire-and-forget), so await the async invocation.
        Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> verify(optimizationService).reconcileStalledStudioOptimizations(
                        INITIALIZED_TIMEOUT.toJavaDuration(), RUNNING_TIMEOUT.toJavaDuration(), BATCH_SIZE));
    }

    @Test
    @DisplayName("skips the reconcile pass entirely when interrupted before execution")
    void skipsWhenInterrupted() {
        job.interrupt();

        job.doJob(mock(JobExecutionContext.class));

        verifyNoInteractions(lockService, optimizationService);
    }
}
