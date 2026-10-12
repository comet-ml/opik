package com.comet.opik.infrastructure.log;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.infrastructure.log.tables.UserLogTableFactory;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.util.retry.Retry;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.groupingBy;

@RequiredArgsConstructor(access = lombok.AccessLevel.PRIVATE)
@Slf4j
class ClickHouseAppender extends AppenderBase<ILoggingEvent> {

    private static final long INSERT_RETRY_ATTEMPTS = 3;
    private static final Duration INSERT_RETRY_MIN_BACKOFF = Duration.ofMillis(100);
    private static final Retry INSERT_RETRY = Retry.backoff(INSERT_RETRY_ATTEMPTS, INSERT_RETRY_MIN_BACKOFF);

    private static ClickHouseAppender instance;

    public static synchronized ClickHouseAppender init(@NonNull UserLogTableFactory userLogTableFactory, int batchSize,
            @NonNull Duration flushIntervalDuration, @NonNull LoggerContext context) {

        if (instance == null) {
            ClickHouseAppender appender = new ClickHouseAppender(userLogTableFactory, flushIntervalDuration, batchSize);
            setInstance(appender);
            appender.setContext(context);
            instance.start();
        }

        return instance;
    }

    private static void setInstance(ClickHouseAppender instance) {
        ClickHouseAppender.instance = instance;
    }

    private final @NonNull UserLogTableFactory userLogTableFactory;
    private final @NonNull Duration flushIntervalDuration;
    private final int batchSize;
    private volatile boolean running = true;

    private final BlockingQueue<ILoggingEvent> logQueue = new LinkedBlockingQueue<>();

    private final AtomicReference<ScheduledExecutorService> scheduler = new AtomicReference<>(
            Executors.newSingleThreadScheduledExecutor());

    @Override
    public void start() {
        // Background flush thread. Wrapped in safeFlushLogs because scheduleAtFixedRate silently
        // cancels the task forever if it ever throws, which would stop persisting user logs for the
        // rest of the JVM's life.
        scheduler.get().scheduleAtFixedRate(this::safeFlushLogs, flushIntervalDuration.toMillis(),
                flushIntervalDuration.toMillis(), TimeUnit.MILLISECONDS);

        super.start();
    }

    private void safeFlushLogs() {
        try {
            flushLogs();
        } catch (Exception e) {
            log.error("Failed to flush logs", e);
        }
    }

    private void flushLogs() {
        if (logQueue.isEmpty()) return;

        List<ILoggingEvent> batch = new ArrayList<>(logQueue.size());
        logQueue.drainTo(batch, logQueue.size());

        if (batch.isEmpty()) return;

        Map<String, List<ILoggingEvent>> eventsPerTable = batch.stream()
                .collect(groupingBy(event -> event.getMDCPropertyMap().getOrDefault(UserLog.MARKER, "")));

        eventsPerTable
                .forEach((userLog, events) -> {

                    if (userLog.isBlank()) {
                        log.error("UserLog marker is not set for events: {}", events.stream()
                                .map(ILoggingEvent::getFormattedMessage)
                                .collect(Collectors.joining(", ")));
                    } else {
                        UserLogTableFactory.UserLogTableDAO tableDAO = userLogTableFactory
                                .getDAO(UserLog.valueOf(userLog));

                        // The batch is already drained out of the queue, so a failed insert would lose
                        // these events outright. Retry transient failures, and put the events back on
                        // the queue if the retries are exhausted so a later flush can pick them up.
                        try {
                            tableDAO
                                    .saveAll(events)
                                    .retryWhen(INSERT_RETRY)
                                    .subscribe(
                                            noop -> {
                                            },
                                            e -> {
                                                log.error("Failed to insert logs", e);
                                                requeue(events);
                                            });
                        } catch (Exception e) {
                            // A synchronous throw from saveAll means the batch itself is rejected before
                            // any I/O (e.g. an event missing workspace_id or rule_id). Requeueing would
                            // fail identically on every later flush, so drop it instead of looping.
                            log.error("Dropping '{}' logs rejected before insert", events.size(), e);
                        }
                    }
                });
    }

    private void requeue(List<ILoggingEvent> events) {
        if (!running) {
            log.warn("ClickHouseAppender is stopped, dropping '{}' logs after failed insert", events.size());
            return;
        }

        for (ILoggingEvent event : events) {
            if (!logQueue.offer(event)) {
                log.warn("Log queue is full, dropping log: {}", event.getFormattedMessage());
            }
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!running) {
            log.debug("ClickHouseAppender is stopped, dropping log: {}", event.getFormattedMessage());
            return;
        }

        boolean added = logQueue.offer(event);
        if (!added) {
            log.warn("Log queue is full, dropping log: {}", event.getFormattedMessage());
        }

        if (logQueue.size() >= batchSize) {
            scheduler.get().execute(this::safeFlushLogs);
        }
    }

    @Override
    public void stop() {
        running = false;
        super.stop();
        flushLogs();
        setInstance(null);
        scheduler.get().shutdown();
        awaitTermination();
        logQueue.clear();
        scheduler.set(Executors.newSingleThreadScheduledExecutor());
    }

    private void awaitTermination() {
        try {
            if (!scheduler.get().awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.get().shutdownNow();
                if (!scheduler.get().awaitTermination(5, TimeUnit.SECONDS)) { // Final attempt
                    log.error("ClickHouseAppender did not terminate");
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            scheduler.get().shutdownNow();
            log.warn("ClickHouseAppender interrupted while waiting for termination", ex);
        }
    }
}
