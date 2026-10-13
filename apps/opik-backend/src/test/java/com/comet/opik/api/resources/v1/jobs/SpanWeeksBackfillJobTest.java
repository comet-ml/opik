package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.SpanWeeksBackfillService;
import com.comet.opik.infrastructure.SpanWeeksBackfillConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SpanWeeksBackfillJobTest {

    @Mock
    private SpanWeeksBackfillService backfillService;

    @Mock
    private LockService lockService;

    private SpanWeeksBackfillJob job;

    @BeforeEach
    void setUp() {
        var config = new SpanWeeksBackfillConfig();
        config.setQueryTimeout(Duration.minutes(4));
        job = new SpanWeeksBackfillJob(backfillService, lockService, config);
    }

    @Test
    void doJob__whenBackfillCompleted__thenTakesNoLockAndRunsNoStep() {
        when(backfillService.isCompleted()).thenReturn(true);

        job.doJob(null);

        verifyNoInteractions(lockService);
        verify(backfillService, never()).runStep();
    }

    @Test
    void doJob__whenBackfillPending__thenRunsOneStepUnderTheLock() {
        when(backfillService.isCompleted()).thenReturn(false);
        when(backfillService.runStep()).thenReturn(Mono.empty());
        when(lockService.bestEffortLock(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        job.doJob(null);

        verify(lockService).bestEffortLock(any(), any(), any(), any(), any());
        verify(backfillService).runStep();
    }

    @Test
    void doJob__whenTheCompletionReadFails__thenDoesNotThrowAndTakesNoLock() {
        when(backfillService.isCompleted()).thenThrow(new IllegalStateException("MySQL unavailable"));

        assertDoesNotThrow(() -> job.doJob(null));

        verifyNoInteractions(lockService);
    }

    @Test
    void doJob__whenTheStepFails__thenDoesNotThrow() {
        when(backfillService.isCompleted()).thenReturn(false);
        when(backfillService.runStep()).thenReturn(Mono.error(new IllegalStateException("ClickHouse timeout")));
        when(lockService.bestEffortLock(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(1));

        assertDoesNotThrow(() -> job.doJob(null));

        verify(backfillService).runStep();
    }
}
