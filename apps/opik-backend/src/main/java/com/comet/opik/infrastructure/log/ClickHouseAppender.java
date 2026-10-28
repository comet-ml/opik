package com.comet.opik.infrastructure.log;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.infrastructure.log.tables.UserLogTableFactory;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static java.util.stream.Collectors.groupingBy;

@Slf4j
class ClickHouseAppender extends AppenderBase<ILoggingEvent> {

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

    // Caps the backlog at this many batches; past it, logs are dropped rather than filling the heap.
    static final int MAX_QUEUED_BATCHES = 10;

    private final @NonNull UserLogTableFactory userLogTableFactory;
    private final @NonNull Duration flushIntervalDuration;
    private final int batchSize;
    private volatile boolean running = true;

    private final BlockingQueue<ILoggingEvent> logQueue;
    private final AtomicBoolean flushPending = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();

    private final AtomicReference<ScheduledExecutorService> scheduler = new AtomicReference<>(
            Executors.newSingleThreadScheduledExecutor());

    ClickHouseAppender(@NonNull UserLogTableFactory userLogTableFactory, @NonNull Duration flushIntervalDuration,
            int batchSize) {
        this.userLogTableFactory = userLogTableFactory;
        this.flushIntervalDuration = flushIntervalDuration;
        this.batchSize = batchSize;
        this.logQueue = new LinkedBlockingQueue<>(batchSize * MAX_QUEUED_BATCHES);
    }

    @Override
    public void start() {
        // Background flush thread
        scheduler.get().scheduleAtFixedRate(this::flushLogs, flushIntervalDuration.toMillis(),
                flushIntervalDuration.toMillis(), TimeUnit.MILLISECONDS);

        super.start();
    }

    private void flushLogs() {
        long droppedCount = dropped.getAndSet(0);
        if (droppedCount > 0) {
            log.warn("Log queue is full, dropped '{}' user-facing logs", droppedCount);
        }

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

                        // Wait for the insert on this dedicated thread, so a slow ClickHouse backs up into the
                        // bounded queue instead of piling up in-flight inserts.
                        try {
                            tableDAO.saveAll(events).block();
                        } catch (RuntimeException e) {
                            log.error("Failed to insert logs", e);
                        }
                    }
                });
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!running) {
            log.debug("ClickHouseAppender is stopped, dropping log: {}", event.getFormattedMessage());
            return;
        }

        if (!logQueue.offer(event)) {
            dropped.incrementAndGet();
        }

        // One pending flush at a time, rather than a task per log once the queue passes batchSize.
        if (logQueue.size() >= batchSize && flushPending.compareAndSet(false, true)) {
            scheduler.get().execute(() -> {
                flushPending.set(false);
                flushLogs();
            });
        }
    }

    @Override
    public void stop() {
        running = false;
        super.stop();
        flushLogs();
        // A stopped appender must not clear the instance that init() handed out to everyone else.
        if (instance == this) {
            setInstance(null);
        }
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
