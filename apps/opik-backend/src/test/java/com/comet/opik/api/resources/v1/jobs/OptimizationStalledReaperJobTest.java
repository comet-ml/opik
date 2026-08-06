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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Optimization Stalled Reaper Job Test")
class OptimizationStalledReaperJobTest {

    private static final Duration INITIALIZED_TIMEOUT = Duration.minutes(5);
    private static final Duration RUNNING_TIMEOUT = Duration.minutes(30);
    private static final Duration RUNNING_HARD_TIMEOUT = Duration.hours(24);
    private static final Duration LOOKBACK_MARGIN = Duration.days(7);
    // Distinct from every other Duration above, so an argument-order slip into bestEffortLock is visible.
    private static final Duration LOCK_DURATION = Duration.minutes(4);
    private static final int BATCH_SIZE = 42;
    // Distinct from BATCH_SIZE so a swap of the two int arguments is visible.
    private static final int CANDIDATE_SCAN_FACTOR = 7;

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
                .runningHardTimeout(RUNNING_HARD_TIMEOUT)
                .lookbackMargin(LOOKBACK_MARGIN)
                .lockDuration(LOCK_DURATION)
                .batchSize(BATCH_SIZE)
                .candidateScanFactor(CANDIDATE_SCAN_FACTOR)
                .build();
        job = new OptimizationStalledReaperJob(optimizationService, lockService, config);
    }

    @Test
    @DisplayName("runs the reconcile pass with the configured timeouts, batch size and scan factor under the lock")
    void runsReconcileUnderLock() {
        // Execute the guarded action (arg 1) so the reconcile call actually fires under the lock.
        when(lockService.bestEffortLock(any(), any(), any(), any(), any(), anyBoolean()))
                .thenAnswer(invocation -> invocation.<Mono<Long>>getArgument(1));
        when(optimizationService.reconcileStalledStudioOptimizations(
                INITIALIZED_TIMEOUT.toJavaDuration(), RUNNING_TIMEOUT.toJavaDuration(),
                RUNNING_HARD_TIMEOUT.toJavaDuration(), LOOKBACK_MARGIN.toJavaDuration(), BATCH_SIZE,
                CANDIDATE_SCAN_FACTOR))
                .thenReturn(Mono.just(3L));

        job.doJob(mock(JobExecutionContext.class));

        // doJob subscribes on boundedElastic (fire-and-forget), so await the async invocation.
        Awaitility.await().atMost(java.time.Duration.ofSeconds(5))
                .untilAsserted(() -> verify(optimizationService).reconcileStalledStudioOptimizations(
                        INITIALIZED_TIMEOUT.toJavaDuration(), RUNNING_TIMEOUT.toJavaDuration(),
                        RUNNING_HARD_TIMEOUT.toJavaDuration(), LOOKBACK_MARGIN.toJavaDuration(), BATCH_SIZE,
                        CANDIDATE_SCAN_FACTOR));

        // Pin the lock arguments too, not just the reconcile ones. lockDuration is the odd knob out — it
        // goes to a different collaborator, and this config now carries four Durations, so handing
        // bestEffortLock runningHardTimeout (24h) instead of lockDuration (4m) would make the reaper
        // effectively run once a day, since the lock is held until expiry. With all-any() matchers and no
        // verification that mistake left the suite green while the @DisplayName still claimed the pass
        // runs "under the lock". The config's own lockDuration < jobInterval invariant is meaningless if
        // the value never reaches the lock.
        verify(lockService).bestEffortLock(
                eq(new LockService.Lock("optimization_stalled_reaper:lock")),
                any(Mono.class),
                any(Mono.class),
                eq(LOCK_DURATION.toJavaDuration()),
                eq(java.time.Duration.ZERO),
                eq(true));
    }

    @Test
    @DisplayName("skips the reconcile pass entirely when interrupted before execution")
    void skipsWhenInterrupted() {
        job.interrupt();

        job.doJob(mock(JobExecutionContext.class));

        verifyNoInteractions(lockService, optimizationService);
    }
}
