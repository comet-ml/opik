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
            var event = new LoggingEvent();
            event.setMDCPropertyMap(Map.of(UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name()));
            appender.doAppend(event);
        }
        insertsBlocked.countDown();
        appender.stop();

        // At most one batch drained before the insert blocked, plus a full queue.
        assertThat(saved.get()).isPositive().isLessThanOrEqualTo(2 * capacity);
    }
}
