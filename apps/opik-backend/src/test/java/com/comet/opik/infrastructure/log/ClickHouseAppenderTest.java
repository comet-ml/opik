package com.comet.opik.infrastructure.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.infrastructure.log.tables.UserLogTableFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("ClickHouse Appender")
class ClickHouseAppenderTest {

    private static final Duration FLUSH_INTERVAL = Duration.ofMillis(50);
    private static final int BATCH_SIZE = 1000;
    private static final int AWAIT_TIMEOUT_SECONDS = 10;

    private ClickHouseAppender appender;

    @AfterEach
    void tearDown() {
        if (appender != null) {
            appender.stop();
        }
    }

    @Test
    @DisplayName("when the insert fails transiently, then the events are retried and still persisted")
    void whenInsertFailsTransiently__thenEventsAreRetriedAndPersisted() {
        var attempts = new AtomicInteger();
        var persisted = new ConcurrentLinkedQueue<ILoggingEvent>();

        // Fail the first attempt, then accept. Without the retry, the drained batch would be lost.
        startAppender(events -> Mono.defer(() -> {
            if (attempts.incrementAndGet() == 1) {
                return Mono.error(new IllegalStateException("transient failure"));
            }
            persisted.addAll(events);
            return Mono.empty();
        }));

        appender.doAppend(event("a message"));

        // The attempt count is what pins Retry.backoff: without it a batch requeued and picked up by a
        // later flush would also persist eventually, so hasSize(1) alone would not prove a retry ran.
        await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .untilAsserted(() -> {
                    assertThat(persisted).hasSize(1);
                    assertThat(attempts).hasValue(2);
                });
    }

    @Test
    @DisplayName("when the batch is rejected before insert, then it is dropped rather than requeued")
    void whenBatchRejectedBeforeInsert__thenItIsDroppedRatherThanRequeued() {
        var attempts = new AtomicInteger();

        // A synchronous throw means the batch is malformed (e.g. missing workspace_id), so it would
        // fail identically forever. Requeueing it would spin the flush thread on a poison batch.
        startAppender(events -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("workspace_id is not set");
        });

        appender.doAppend(event("a malformed message"));

        await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(attempts).hasValue(1));

        // Give several further flush intervals a chance to re-attempt the dropped batch.
        await().during(FLUSH_INTERVAL.multipliedBy(10))
                .atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(attempts).hasValue(1));
    }

    @Test
    @DisplayName("when a flush throws, then subsequent flushes still run")
    void whenFlushThrows__thenSubsequentFlushesStillRun() {
        var persisted = new ConcurrentLinkedQueue<ILoggingEvent>();
        var failNext = new AtomicInteger(1);

        // A throw escaping into scheduleAtFixedRate would cancel the periodic flush permanently,
        // silently stopping log persistence for the rest of the JVM's life.
        startAppender(events -> {
            if (failNext.getAndSet(0) == 1) {
                throw new IllegalStateException("failure inside flush");
            }
            persisted.addAll(events);
            return Mono.empty();
        });

        appender.doAppend(event("first"));

        // The first batch is dropped (it was rejected before insert), so the surviving scheduler is
        // proven by a later event still being persisted rather than by the failed batch reappearing.
        await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(failNext).hasValue(0));

        appender.doAppend(event("second"));

        await().atMost(AWAIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .untilAsserted(() -> assertThat(persisted).hasSize(1));
    }

    private void startAppender(SaveAll saveAll) {
        UserLogTableFactory tableFactory = userLog -> saveAll::apply;

        appender = ClickHouseAppender.init(tableFactory, BATCH_SIZE, FLUSH_INTERVAL, new LoggerContext());
    }

    private ILoggingEvent event(String message) {
        var event = new LoggingEvent();
        event.setLevel(Level.INFO);
        event.setMessage(message);
        event.setMDCPropertyMap(Map.of(UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name()));
        return event;
    }

    @FunctionalInterface
    private interface SaveAll {
        Mono<Void> apply(List<ILoggingEvent> events);
    }
}
