package com.comet.opik.infrastructure.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.classic.spi.LoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.infrastructure.ClickHouseLogAppenderConfig;
import com.comet.opik.infrastructure.log.tables.UserLogTableFactory;
import com.google.common.util.concurrent.Uninterruptibles;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Appenders are built directly, not via init(): that returns the process-wide instance other tests may have created.
class ClickHouseAppenderTest {

    private final Logger appenderLogger = (Logger) LoggerFactory.getLogger(ClickHouseAppender.class);
    private final ListAppender<ILoggingEvent> appenderLogs = new ListAppender<>();
    private final CountDownLatch resumeScheduler = new CountDownLatch(1);

    @BeforeEach
    void captureAppenderLogs() {
        appenderLogs.start();
        appenderLogger.addAppender(appenderLogs);
    }

    @AfterEach
    void releaseResources() {
        resumeScheduler.countDown();
        appenderLogger.detachAppender(appenderLogs);
    }

    @Test
    void dropsLogsOnlyWhileAnInsertIsInFlightAndTheQueueIsFull() {
        var insertDone = new CompletableFuture<Void>();
        var inFlight = new AtomicInteger();
        var maxInFlight = new AtomicInteger();
        var batchSizes = new CopyOnWriteArrayList<Integer>();

        var appender = new ClickHouseAppender(
                trackedInserts(insertDone, inFlight, maxInFlight, batchSizes), config(10, 2), pausedScheduler());
        appender.setContext(new LoggerContext());
        appender.start();

        // The 21st append finds the queue full and forces a flush: 10 go to the insert, 11 stay queued.
        append(appender, 21);
        assertThat(batchSizes).containsExactly(10);

        // 9 more fill the queue again; with that insert still in flight, the last 5 are dropped.
        append(appender, 14);
        assertThat(inFlight).hasValue(1);

        insertDone.complete(null);
        resumeScheduler.countDown();
        appender.stop();

        assertThat(batchSizes).containsExactly(10, 10, 10);
        assertThat(maxInFlight).hasValue(1);
        assertThat(warnings()).containsExactly("Log queue is full, dropped '5' user-facing logs");
    }

    @Test
    void stopWaitsForTheInFlightInsertBeforeDraining() throws InterruptedException {
        var insertDone = new CompletableFuture<Void>();
        var inFlight = new AtomicInteger();
        var maxInFlight = new AtomicInteger();
        var batchSizes = new CopyOnWriteArrayList<Integer>();

        var appender = new ClickHouseAppender(
                trackedInserts(insertDone, inFlight, maxInFlight, batchSizes), config(10, 2), pausedScheduler());
        appender.setContext(new LoggerContext());
        appender.start();

        append(appender, 21); // forces one insert of 10, which stays in flight; 11 stay queued
        resumeScheduler.countDown();

        var stopThread = new Thread(appender::stop);
        stopThread.start();
        // Parked inside drainOnStop(): waiting on the in-flight insert, or, if it didn't wait, on a second insert
        // that would already be in batchSizes.
        await().atMost(5, TimeUnit.SECONDS).until(() -> stopThread.getState() == Thread.State.TIMED_WAITING
                && Arrays.stream(stopThread.getStackTrace()).anyMatch(f -> f.getMethodName().equals("drainOnStop")));
        assertThat(batchSizes).containsExactly(10);

        insertDone.complete(null);
        stopThread.join(TimeUnit.SECONDS.toMillis(5));
        assertThat(stopThread.isAlive()).isFalse();

        assertThat(batchSizes).containsExactly(10, 10, 1);
        assertThat(maxInFlight).hasValue(1);
    }

    @Test
    void forcedFlushMakesRoomWhenNoInsertIsInFlight() {
        var saved = new AtomicInteger();

        var appender = new ClickHouseAppender(
                userLog -> events -> Mono.<Void>fromRunnable(() -> saved.addAndGet(events.size())),
                config(10, 1), pausedScheduler());
        appender.setContext(new LoggerContext());
        appender.start();

        // The scheduler is paused, so only the forced flushes on a full queue can have inserted these.
        append(appender, 25);
        assertThat(saved).hasValue(20);

        resumeScheduler.countDown();
        appender.stop();

        assertThat(saved).hasValue(25);
        assertThat(warnings()).isEmpty();
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
                config(100, 10), Executors.newSingleThreadScheduledExecutor());
        appender.setContext(new LoggerContext());
        appender.start();

        for (int i = 0; i < 5; i++) {
            appender.doAppend(event(UserLog.ALERT_EVENT));
            appender.doAppend(event(UserLog.AUTOMATION_RULE_EVALUATOR));
        }
        appender.stop();

        assertThat(alertInsertAttempted).isTrue();
        assertThat(saved).hasValue(5);
    }

    // Inserts complete when insertDone does. The count drops before the completion reaches the appender, which
    // may start the next insert right away.
    private static UserLogTableFactory trackedInserts(CompletableFuture<Void> insertDone, AtomicInteger inFlight,
            AtomicInteger maxInFlight, List<Integer> batchSizes) {
        return userLog -> events -> Mono.defer(() -> {
            batchSizes.add(events.size());
            maxInFlight.accumulateAndGet(inFlight.incrementAndGet(), Math::max);
            return Mono.fromFuture(insertDone);
        }).doOnTerminate(inFlight::decrementAndGet);
    }

    // Holds back every scheduled flush until resumeScheduler opens, leaving only the inline ones.
    private ScheduledExecutorService pausedScheduler() {
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.execute(() -> Uninterruptibles.awaitUninterruptibly(resumeScheduler));
        return scheduler;
    }

    private List<String> warnings() {
        return appenderLogs.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    private static ClickHouseLogAppenderConfig config(int batchSize, int maxQueuedBatches) {
        var config = new ClickHouseLogAppenderConfig();
        config.setBatchSize(batchSize);
        config.setMaxQueuedBatches(maxQueuedBatches);
        config.setFlushIntervalDuration(Duration.ofMinutes(1));
        config.setShutdownTimeout(Duration.ofSeconds(5));
        return config;
    }

    private static void append(ClickHouseAppender appender, int count) {
        for (int i = 0; i < count; i++) {
            appender.doAppend(event(UserLog.AUTOMATION_RULE_EVALUATOR));
        }
    }

    private static LoggingEvent event(UserLog userLog) {
        var event = new LoggingEvent();
        event.setMDCPropertyMap(Map.of(UserLog.MARKER, userLog.name()));
        return event;
    }
}
