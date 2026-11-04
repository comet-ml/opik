package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.SpanWeeksBackfillService;
import com.comet.opik.infrastructure.DatabaseAnalyticsDataModelConfig;
import com.comet.opik.infrastructure.SpanWeeksBackfillConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.publisher.PublisherProbe;

import static com.comet.opik.infrastructure.lock.LockService.Lock;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpanWeeksBackfillJobTest {

    private static final Lock RUN_LOCK = new Lock("span_weeks_backfill:run_lock");

    @Mock
    private SpanWeeksBackfillService backfillService;

    @Mock
    private LockService lockService;

    private SpanWeeksBackfillConfig config;
    private SpanWeeksBackfillJob job;

    @BeforeEach
    void setUp() {
        config = SpanWeeksBackfillConfig.builder()
                .interval(Duration.minutes(5))
                .weeksPerChunk(4)
                .maxThreads(4)
                .queryTimeout(Duration.minutes(4))
                .lockWaitTime(Duration.seconds(1))
                .build();
        job = newJob(true);
    }

    private SpanWeeksBackfillJob newJob(boolean backfillEnabled) {
        return new SpanWeeksBackfillJob(backfillService, lockService, config,
                DatabaseAnalyticsDataModelConfig.builder().spanWeeksBackfillEnabled(backfillEnabled).build());
    }

    private void lockRunsTheAction() {
        when(lockService.bestEffortLock(eq(RUN_LOCK), any(), any(), eq(config.interval().toJavaDuration()),
                eq(config.lockWaitTime().toJavaDuration()), eq(true)))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    @Test
    void doJob__whenBackfillDisabled__thenReadsNothing() {
        newJob(false).doJob(null);

        verifyNoInteractions(backfillService, lockService);
    }

    @Test
    void doJob__whenBackfillCompleted__thenTakesNoLockAndRunsNoStep() {
        when(backfillService.isCompleted()).thenReturn(Mono.just(true));

        job.doJob(null);

        verifyNoInteractions(lockService);
        verify(backfillService, never()).runStep();
    }

    @Test
    void doJob__whenBackfillPending__thenRunsOneStepHoldingTheLockForTheInterval() {
        when(backfillService.isCompleted()).thenReturn(Mono.just(false));
        when(backfillService.runStep()).thenReturn(Mono.empty());
        lockRunsTheAction();

        job.doJob(null);

        verify(lockService).bestEffortLock(eq(RUN_LOCK), any(), any(), eq(config.interval().toJavaDuration()),
                eq(config.lockWaitTime().toJavaDuration()), eq(true));
        verify(backfillService).runStep();
    }

    @Test
    void doJob__whenTheCompletionReadFails__thenDoesNotThrowAndTakesNoLock() {
        when(backfillService.isCompleted()).thenReturn(Mono.error(new IllegalStateException("MySQL unavailable")));

        assertDoesNotThrow(() -> job.doJob(null));

        verifyNoInteractions(lockService);
    }

    @Test
    void doJob__whenTheStepFails__thenDoesNotThrow() {
        when(backfillService.isCompleted()).thenReturn(Mono.just(false));
        when(backfillService.runStep()).thenReturn(Mono.error(new IllegalStateException("ClickHouse timeout")));
        lockRunsTheAction();

        assertDoesNotThrow(() -> job.doJob(null));

        verify(backfillService).runStep();
    }

    @Test
    void interrupt__whenAStepIsRunning__thenCancelsItWithoutBlockingTheTick() {
        PublisherProbe<Void> step = PublisherProbe.of(Mono.never());
        when(backfillService.isCompleted()).thenReturn(Mono.just(false));
        when(backfillService.runStep()).thenReturn(step.mono());
        lockRunsTheAction();

        // The step never ends, so a blocking tick would never return.
        job.doJob(null);
        job.interrupt();

        step.assertWasSubscribed();
        assertThat(step.wasCancelled()).isTrue();
    }
}
