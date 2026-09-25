package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.domain.AnnotationQueueRoutingBufferService;
import com.comet.opik.infrastructure.AnnotationQueueRoutingConfig;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.lock.LockService;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The job only orchestrates — lock, schedule, interruption — so that is all this covers. What a flush does
 * to Redis and the stream is {@code AnnotationQueueRoutingIntegrationTest}'s.
 */
@ExtendWith(MockitoExtension.class)
class AnnotationQueueRoutingFlushJobTest {

    private static final Duration LOCK_TIME = Duration.milliseconds(1500);
    private static final Duration LOCK_WAIT_TIME = Duration.milliseconds(200);

    @Mock
    private FeatureFlags featureFlags;

    @Mock
    private AnnotationQueueRoutingBufferService bufferService;

    @Mock
    private LockService lockService;

    private final AtomicInteger flushes = new AtomicInteger();
    private final AtomicBoolean cancelled = new AtomicBoolean();

    private AnnotationQueueRoutingFlushJob job;

    @BeforeEach
    void setUp() {
        job = new AnnotationQueueRoutingFlushJob(config(), featureFlags, bufferService, lockService);
        lenient().when(featureFlags.isAnnotationQueueAutomationEnabled()).thenReturn(true);
    }

    @Test
    @DisplayName("A disabled feature never takes the lock")
    void disabledFeatureNeverTakesTheLock() {
        when(featureFlags.isAnnotationQueueAutomationEnabled()).thenReturn(false);

        job.doJob(null);

        verify(lockService, never()).bestEffortLock(any(), any(), any(), any(), any(), eq(true));
        verify(bufferService, never()).flush();
    }

    @Test
    @DisplayName("The flush runs under a hold-until-expiry lock sized by the config")
    void flushRunsUnderHoldUntilExpiryLock() {
        when(bufferService.flush()).thenReturn(Mono.fromCallable(() -> (long) flushes.incrementAndGet()));
        lockAcquired();

        job.doJob(null);

        assertThat(flushes).hasValue(1);
        verify(lockService).bestEffortLock(any(), any(), any(), eq(LOCK_TIME.toJavaDuration()),
                eq(LOCK_WAIT_TIME.toJavaDuration()), eq(true));
    }

    @Test
    @DisplayName("Another instance holding the lock means this run flushes nothing")
    void lockHeldElsewhereSkipsTheFlush() {
        when(bufferService.flush()).thenReturn(Mono.fromCallable(() -> (long) flushes.incrementAndGet()));
        // The lock service runs the fallback instead of the action.
        when(lockService.bestEffortLock(any(), any(), any(), any(), any(), eq(true)))
                .thenAnswer(invocation -> invocation.<Mono<Void>>getArgument(2));

        job.doJob(null);

        assertThat(flushes).hasValue(0);
    }

    @Test
    @DisplayName("Interrupting cancels the in-flight flush and stops later runs")
    void interruptCancelsInFlightFlushAndStopsLaterRuns() throws Exception {
        when(bufferService.flush()).thenReturn(Mono.<Long>never().doOnCancel(() -> cancelled.set(true)));
        lockAcquired();

        job.doJob(null);
        job.interrupt();

        assertThat(cancelled).isTrue();

        // A run after the interrupt is a no-op: the buffer is not asked to flush again.
        job.doJob(null);
        verify(bufferService, times(1)).flush();
    }

    private void lockAcquired() {
        when(lockService.bestEffortLock(any(), any(), any(), any(), any(), eq(true)))
                .thenAnswer(invocation -> invocation.<Mono<Void>>getArgument(1));
    }

    private static AnnotationQueueRoutingConfig config() {
        return AnnotationQueueRoutingConfig.builder()
                .jobLockTime(LOCK_TIME)
                .jobLockWaitTime(LOCK_WAIT_TIME)
                .build();
    }
}
