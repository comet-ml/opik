package com.comet.opik.infrastructure.log;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.comet.opik.domain.evaluators.UserLog;
import com.google.common.util.concurrent.Uninterruptibles;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ClickHouseAppenderTest {

    @Test
    void dropsLogsInsteadOfQueueingWithoutLimitWhileInsertsAreSlow() {
        int batchSize = 10;
        int capacity = batchSize * ClickHouseAppender.MAX_QUEUED_BATCHES;
        var insertsBlocked = new CountDownLatch(1);
        var saved = new AtomicInteger();

        // Not via init(): that returns the process-wide instance other tests may have created.
        var appender = new ClickHouseAppender(
                userLog -> events -> Mono.fromRunnable(() -> {
                    Uninterruptibles.awaitUninterruptibly(insertsBlocked);
                    saved.addAndGet(events.size());
                }),
                Duration.ofMinutes(1), batchSize);
        appender.setContext(new LoggerContext());
        appender.start();

        for (int i = 0; i < 10_000; i++) {
            appender.doAppend(event(UserLog.AUTOMATION_RULE_EVALUATOR));
        }
        insertsBlocked.countDown();
        appender.stop();

        // At most one batch drained before the insert blocked, plus a full queue.
        assertThat(saved.get()).isPositive().isLessThanOrEqualTo(2 * capacity);
    }

    @Test
    void failedInsertForOneTableDoesNotStopTheOther() {
        var saved = new AtomicInteger();
        var alertInsertAttempted = new AtomicBoolean();

        var appender = new ClickHouseAppender(
                userLog -> switch (userLog) {
                    case ALERT_EVENT -> events -> Mono.fromRunnable(() -> alertInsertAttempted.set(true))
                            .then(Mono.<Void>error(new IllegalStateException("insert failed")));
                    case AUTOMATION_RULE_EVALUATOR ->
                        events -> Mono.<Void>fromRunnable(() -> saved.addAndGet(events.size()));
                },
                Duration.ofMinutes(1), 100);
        appender.setContext(new LoggerContext());
        appender.start();

        for (int i = 0; i < 5; i++) {
            appender.doAppend(event(UserLog.ALERT_EVENT));
            appender.doAppend(event(UserLog.AUTOMATION_RULE_EVALUATOR));
        }
        appender.stop();

        assertThat(alertInsertAttempted).isTrue();
        assertThat(saved.get()).isEqualTo(5);
    }

    private static LoggingEvent event(UserLog userLog) {
        var event = new LoggingEvent();
        event.setMDCPropertyMap(Map.of(UserLog.MARKER, userLog.name()));
        return event;
    }
}
