package com.comet.opik.api.resources.v1.jobs;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.comet.opik.TestConfigUtils;
import com.comet.opik.domain.topics.TopicVectorsTickService;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.lock.LockService;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongCounterBuilder;
import io.opentelemetry.api.metrics.Meter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_SELF;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@DisplayName("topic vectors job")
class TopicVectorsJobTest {

    private static final Duration LEASE = Duration.ofSeconds(95);

    private final TopicsConfig config = TestConfigUtils.loadConfigTest().getTopics();
    private final TopicVectorsTickService tickService = mock(TopicVectorsTickService.class);
    private final LockService lockService = mock(LockService.class);
    private final LongCounter ticks = mock(LongCounter.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private final Logger logger = (Logger) LoggerFactory.getLogger(TopicVectorsJob.class);
    private TopicVectorsJob job;

    @BeforeEach
    void setUp() {
        config.setEnabled(true);
        when(tickService.leaderLease()).thenReturn(LEASE);
        job = new TopicVectorsJob(config, tickService, lockService, meterOf(ticks));
        // Only the firings' own counts matter below; the start values have their own test.
        clearInvocations(ticks);
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.DEBUG);
    }

    @AfterEach
    void tearDown() {
        logger.detachAppender(appender);
        logger.setLevel(null);
    }

    @Test
    @DisplayName("an interrupt that lands while the tick is being subscribed still disposes it")
    void interruptDuringSubscribe() {
        var cancelled = new AtomicBoolean();
        when(leaderLock()).thenReturn(Mono.defer(() -> {
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
        when(leaderLock()).thenReturn(Mono.never().doOnCancel(() -> cancelled.set(true)));

        job.doJob(null);
        assertThat(cancelled).isFalse();
        interrupt();

        assertThat(cancelled).isTrue();
    }

    @Test
    @DisplayName("the leader ticks only when the tick lock is free, held for one interval less the jitter margin")
    void tickLock() {
        var action = new AtomicReference<Mono<?>>();
        when(leaderLock()).thenAnswer(invocation -> {
            action.set(invocation.getArgument(1));
            return invocation.getArgument(1);
        });
        when(lockService.lockUsingToken(TopicVectorsJob.TICK_LOCK, Duration.ofSeconds(59)))
                .thenReturn(Mono.just(true), Mono.just(false));
        var start = Instant.parse("2026-11-20T12:00:00Z");

        job.tickIfDue(start);
        verify(tickService).tick(start);
        verify(ticks).add(1, outcome(TopicVectorsJob.RAN));

        job.tickIfDue(start.plusSeconds(30));
        verify(tickService).tick(start);
        verifyNoMoreInteractions(tickService);
        verify(ticks).add(1, outcome(TopicVectorsJob.NOT_DUE));
        verifyNoMoreInteractions(ticks);
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).contains("lock 'topics:vectors:tick'");
        });

        // doJob takes the leader lock for the tick's lease, then runs the same check.
        job.doJob(null);
        verify(lockService).bestEffortLock(eq(TopicVectorsJob.LEADER_LOCK), eq(action.get()), any(Mono.class),
                eq(LEASE), eq(Duration.ZERO));
    }

    @Test
    @DisplayName("a pod that can't take the leader lock skips, logged at DEBUG with the lock's name")
    void leaderHeld() {
        when(leaderLock()).thenAnswer(invocation -> invocation.getArgument(2));

        job.doJob(null);

        verify(tickService).leaderLease();
        verifyNoMoreInteractions(tickService);
        verify(ticks).add(1, outcome(TopicVectorsJob.LOCK_HELD));
        verifyNoMoreInteractions(ticks);
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.DEBUG);
            assertThat(event.getFormattedMessage()).contains("lock 'topics:vectors:leader'");
        });
    }

    @Test
    @DisplayName("with topics on, every tick outcome starts at 0; with topics off the job publishes nothing")
    void outcomesStartAtZero() {
        var enabledTicks = mock(LongCounter.class);
        new TopicVectorsJob(config, tickService, lockService, meterOf(enabledTicks));
        for (var outcome : TopicVectorsJob.OUTCOMES) {
            verify(enabledTicks).add(0, outcome(outcome));
        }
        assertThat(TopicVectorsJob.OUTCOMES).containsExactly("ran", "not_due", "lock_held", "failed");

        var disabledTicks = mock(LongCounter.class);
        var disabled = TestConfigUtils.loadConfigTest().getTopics();
        disabled.setEnabled(false);
        new TopicVectorsJob(disabled, tickService, lockService, meterOf(disabledTicks));
        verifyNoInteractions(disabledTicks);
    }

    @Test
    @DisplayName("a tick that throws is counted once as failed and logged at ERROR; the lock fallback does not run")
    void tickFails() {
        var fallbackRan = new AtomicBoolean();
        when(leaderLock()).thenAnswer(invocation -> {
            Mono<Object> action = invocation.getArgument(1);
            Mono<Void> fallback = invocation.getArgument(2);
            // The lock service runs the fallback when the action fails with a Redis error.
            return action.onErrorResume(error -> fallback.doOnSuccess(__ -> fallbackRan.set(true)).then(Mono.empty()));
        });
        when(lockService.lockUsingToken(TopicVectorsJob.TICK_LOCK, Duration.ofSeconds(59)))
                .thenReturn(Mono.just(true));
        // The tick's start is the job's own clock reading, so only its type is known here.
        when(tickService.tick(any(Instant.class)))
                .thenThrow(new IllegalStateException("ai-labels has no task 'trace_summary'"));

        job.doJob(null);

        verify(ticks).add(1, outcome(TopicVectorsJob.FAILED));
        verifyNoMoreInteractions(ticks);
        assertThat(fallbackRan).isFalse();
        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.ERROR);
            assertThat(event.getFormattedMessage()).isEqualTo("Topic vectors tick failed");
        });
    }

    @Test
    @DisplayName("a tick lock that can't be read, or a lock service error, is counted as failed")
    void lockErrorsFail() {
        when(leaderLock()).thenAnswer(invocation -> invocation.getArgument(1));
        when(lockService.lockUsingToken(TopicVectorsJob.TICK_LOCK, Duration.ofSeconds(59)))
                .thenReturn(Mono.error(new IllegalStateException("redis down")));

        job.doJob(null);

        verify(tickService).leaderLease();
        verifyNoMoreInteractions(tickService);
        verify(ticks).add(1, outcome(TopicVectorsJob.FAILED));

        when(leaderLock()).thenReturn(Mono.error(new IllegalStateException("lock service broke")));

        job.doJob(null);

        verify(ticks, times(2)).add(1, outcome(TopicVectorsJob.FAILED));
        verifyNoMoreInteractions(ticks);
    }

    /**
     * The leader lock call as the job makes it: the lock, the tick's lease and no wait are exact; the action and the
     * fallback are the job's own Monos, so only their type is known here.
     */
    @SuppressWarnings("unchecked")
    private Mono<Object> leaderLock() {
        return lockService.bestEffortLock(eq(TopicVectorsJob.LEADER_LOCK), any(Mono.class), any(Mono.class),
                eq(LEASE), eq(Duration.ZERO));
    }

    private static Attributes outcome(String outcome) {
        return Attributes.of(TopicVectorsJob.OUTCOME, outcome);
    }

    private static Meter meterOf(LongCounter counter) {
        var meter = mock(Meter.class);
        var builder = mock(LongCounterBuilder.class, RETURNS_SELF);
        when(builder.build()).thenReturn(counter);
        when(meter.counterBuilder("topics.vectors.ticks")).thenReturn(builder);
        return meter;
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
