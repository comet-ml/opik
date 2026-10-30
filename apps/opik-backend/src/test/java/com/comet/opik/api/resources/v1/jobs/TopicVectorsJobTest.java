package com.comet.opik.api.resources.v1.jobs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.comet.opik.domain.topics.TopicVectorsTickService;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("topic vectors job")
class TopicVectorsJobTest {

    private final TopicsConfig config = new TopicsConfig();
    private final TopicVectorsTickService tickService = mock(TopicVectorsTickService.class);
    private final LockService lockService = mock(LockService.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(TopicVectorsJob.class);
    private TopicVectorsJob job;

    @BeforeEach
    void setUp() {
        config.setEnabled(true);
        when(tickService.leaderLease()).thenReturn(Duration.ofSeconds(95));
        job = new TopicVectorsJob(config, tickService, lockService);
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
    }

    @Test
    @DisplayName("an interrupt that lands while the tick is being subscribed still disposes it")
    void interruptDuringSubscribe() {
        var cancelled = new AtomicBoolean();
        when(lockService.bestEffortLock(any(), any(), any(), any(), any())).thenReturn(Mono.defer(() -> {
            // The interrupt arrives after subscribe() started and before doJob() stored the subscription.
            interrupt();
            return Mono.never();
        }).doOnCancel(() -> cancelled.set(true)));

        job.doJob(null);

        assertThat(cancelled).isTrue();
    }

    @Test
    @DisplayName("an interrupt after the subscription is stored disposes it")
    void interruptAfterSubscribe() {
        var cancelled = new AtomicBoolean();
        when(lockService.bestEffortLock(any(), any(), any(), any(), any()))
                .thenReturn(Mono.never().doOnCancel(() -> cancelled.set(true)));

        job.doJob(null);
        assertThat(cancelled).isFalse();
        interrupt();

        assertThat(cancelled).isTrue();
    }

    @Test
    @DisplayName("the leader ticks only when the tick lock is free, held for one interval less the jitter margin")
    void tickLock() {
        var action = new AtomicReference<Mono<?>>();
        when(lockService.bestEffortLock(eq(TopicVectorsJob.LEADER_LOCK), any(), any(), eq(Duration.ofSeconds(95)),
                eq(Duration.ZERO))).thenAnswer(invocation -> {
                    action.set(invocation.getArgument(1));
                    return invocation.getArgument(1);
                });
        when(lockService.lockUsingToken(TopicVectorsJob.TICK_LOCK, Duration.ofSeconds(59)))
                .thenReturn(Mono.just(true), Mono.just(false));

        job.doJob(null);
        verify(tickService).tick(any());

        job.doJob(null);
        verify(tickService).tick(any());
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("lock 'topics:vectors:tick'");
        });
    }

    @Test
    @DisplayName("a pod that can't take the leader lock skips, logged at INFO with the lock's name")
    void leaderHeld() {
        when(lockService.bestEffortLock(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));

        job.doJob(null);

        verify(tickService, never()).tick(any());
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.INFO);
            assertThat(event.getFormattedMessage()).contains("lock 'topics:vectors:leader'");
        });
    }

    @Test
    @DisplayName("the tick slot is the interval less 1 s, or less a tenth of a short interval")
    void tickSlot() {
        assertThat(job.tickSlot()).isEqualTo(Duration.ofSeconds(59));
        config.getVectors().setTickInterval(io.dropwizard.util.Duration.seconds(5));
        assertThat(job.tickSlot()).isEqualTo(Duration.ofMillis(4_500));
    }

    private void interrupt() {
        try {
            job.interrupt();
        } catch (org.quartz.UnableToInterruptJobException exception) {
            throw new IllegalStateException(exception);
        }
    }
}
