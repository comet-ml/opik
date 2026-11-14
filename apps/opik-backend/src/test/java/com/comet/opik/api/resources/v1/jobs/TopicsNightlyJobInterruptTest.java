package com.comet.opik.api.resources.v1.jobs;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.domain.topics.TopicsNightlyService;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("topics nightly job: interrupt")
class TopicsNightlyJobInterruptTest {

    private static final Duration LEASE = Duration.ofHours(3);

    private final TopicsConfig config = TestConfigUtils.loadConfigTest().getTopics();
    private final TopicsNightlyService nightlyService = mock(TopicsNightlyService.class);
    private final LockService lockService = mock(LockService.class);
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private TopicsNightlyJob job;

    @BeforeEach
    void setUp() {
        config.setEnabled(true);
        config.getNightly().setEnabled(true);
        when(nightlyService.lease()).thenReturn(LEASE);
        job = new TopicsNightlyJob(config, nightlyService, lockService);
    }

    @Test
    @DisplayName("an interrupt cancels the night in flight, and a later firing takes no lock and starts no night")
    void interruptCancelsTheNight() throws Exception {
        // The night lock's night that is still going when the pod shuts down.
        when(lockService.bestEffortLock(eq(TopicsNightlyService.NIGHT_LOCK), any(), any(), eq(LEASE),
                eq(Duration.ZERO))).thenReturn(Mono.never().doOnCancel(() -> cancelled.set(true)));

        job.doJob(null);
        job.interrupt();
        assertThat(cancelled).isTrue();

        job.doJob(null);
        verify(lockService).bestEffortLock(eq(TopicsNightlyService.NIGHT_LOCK), any(), any(), any(), any());
        verify(nightlyService, never()).runNight(any());
    }

    @Test
    @DisplayName("an interrupt that comes between the subscribe and the store of the subscription still cancels the "
            + "night, exactly once")
    void interruptDuringSubscribe() {
        var cancels = new AtomicInteger();
        // The interrupt lands while the night subscribes: there is no subscription to dispose yet.
        when(lockService.bestEffortLock(eq(TopicsNightlyService.NIGHT_LOCK), any(), any(), eq(LEASE),
                eq(Duration.ZERO))).thenReturn(Mono.never()
                        .doOnSubscribe(subscription -> {
                            try {
                                job.interrupt();
                            } catch (Exception exception) {
                                throw new IllegalStateException(exception);
                            }
                        })
                        .doOnCancel(cancels::incrementAndGet));

        job.doJob(null);

        assertThat(cancels).hasValue(1);
        verify(nightlyService, never()).runNight(any());
    }
}
