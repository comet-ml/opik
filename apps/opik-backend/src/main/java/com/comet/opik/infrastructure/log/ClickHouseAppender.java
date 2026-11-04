package com.comet.opik.infrastructure.log;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.infrastructure.ClickHouseLogAppenderConfig;
import com.comet.opik.infrastructure.log.tables.UserLogTableFactory;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
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

    public static synchronized ClickHouseAppender init(@NonNull UserLogTableFactory userLogTableFactory,
            @NonNull ClickHouseLogAppenderConfig config, @NonNull LoggerContext context) {

        if (instance == null) {
            ClickHouseAppender appender = new ClickHouseAppender(userLogTableFactory, config,
                    Executors.newSingleThreadScheduledExecutor());
            setInstance(appender);
            appender.setContext(context);
            instance.start();
        }

        return instance;
    }

    private static void setInstance(ClickHouseAppender instance) {
        ClickHouseAppender.instance = instance;
    }

    // A stopped appender must not clear the instance that init() handed out to everyone else.
    private static synchronized void clearInstance(ClickHouseAppender appender) {
        if (instance == appender) {
            setInstance(null);
        }
    }

    private final @NonNull UserLogTableFactory userLogTableFactory;
    private final @NonNull Duration flushIntervalDuration;
    private final @NonNull Duration shutdownTimeout;
    private final int batchSize;
    private volatile boolean running = true;

    private final BlockingQueue<ILoggingEvent> logQueue;
    // Set while an insert is in flight, so there is at most one at a time.
    private final AtomicBoolean flushPending = new AtomicBoolean();
    // Set while a threshold-triggered flush is queued, so appends don't queue one each.
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();

    private final AtomicReference<ScheduledExecutorService> scheduler;

    ClickHouseAppender(@NonNull UserLogTableFactory userLogTableFactory, @NonNull ClickHouseLogAppenderConfig config,
            @NonNull ScheduledExecutorService scheduler) {
        this.userLogTableFactory = userLogTableFactory;
        this.flushIntervalDuration = config.getFlushIntervalDuration();
        this.shutdownTimeout = config.getShutdownTimeout();
        this.batchSize = config.getBatchSize();
        this.logQueue = new LinkedBlockingQueue<>(batchSize * config.getMaxQueuedBatches());
        this.scheduler = new AtomicReference<>(scheduler);
    }

    @Override
    public void start() {
        // Background flush thread
        scheduler.get().scheduleAtFixedRate(this::flushOnTick, flushIntervalDuration.toMillis(),
                flushIntervalDuration.toMillis(), TimeUnit.MILLISECONDS);

        super.start();
    }

    private void flushOnTick() {
        reportDropped();
        flushLogs();
    }

    private void reportDropped() {
        long droppedCount = dropped.getAndSet(0);
        if (droppedCount > 0) {
            log.warn("Log queue is full, dropped '{}' user-facing logs", droppedCount);
        }
    }

    private void flushLogs() {
        if (!flushPending.compareAndSet(false, true)) {
            return; // an insert is already in flight
        }

        List<ILoggingEvent> batch = new ArrayList<>(batchSize);
        logQueue.drainTo(batch, batchSize);

        if (batch.isEmpty()) {
            flushPending.set(false);
            return;
        }

        // The flag clears when the insert completes, not before: a slow ClickHouse backs up into the bounded queue.
        insert(batch)
                .doFinally(signal -> {
                    flushPending.set(false);
                    if (running && logQueue.size() >= batchSize) {
                        submit(this::flushLogs);
                    }
                })
                .subscribe();
    }

    private Mono<Void> insert(List<ILoggingEvent> batch) {
        Map<String, List<ILoggingEvent>> eventsPerTable = batch.stream()
                .collect(groupingBy(event -> event.getMDCPropertyMap().getOrDefault(UserLog.MARKER, "")));

        return Mono.when(eventsPerTable.entrySet().stream()
                .map(entry -> save(entry.getKey(), entry.getValue()))
                .toList());
    }

    private Mono<Void> save(String userLogMarker, List<ILoggingEvent> events) {
        if (userLogMarker.isBlank()) {
            log.error("UserLog marker is not set for events: {}", events.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .collect(Collectors.joining(", ")));
            return Mono.empty();
        }

        return Mono.defer(() -> userLogTableFactory.getDAO(UserLog.valueOf(userLogMarker)).saveAll(events))
                .onErrorResume(e -> {
                    log.error("Failed to insert logs", e);
                    return Mono.empty();
                });
    }

    private void submit(Runnable task) {
        try {
            scheduler.get().execute(task);
        } catch (RejectedExecutionException e) {
            log.debug("ClickHouseAppender is stopping, skipping flush");
        }
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (!running) {
            log.debug("ClickHouseAppender is stopped, dropping log: {}", event.getFormattedMessage());
            return;
        }

        if (!logQueue.offer(event)) {
            flushLogs(); // makes room unless an insert is already in flight
            if (!logQueue.offer(event)) {
                dropped.incrementAndGet();
            }
        }

        if (logQueue.size() >= batchSize && flushScheduled.compareAndSet(false, true)) {
            submit(() -> {
                flushScheduled.set(false);
                flushLogs();
            });
        }
    }

    @Override
    public void stop() {
        running = false;
        super.stop();
        clearInstance(this);
        scheduler.get().shutdown();
        awaitTermination();
        drainOnStop();
        reportDropped();
        logQueue.clear();
        flushPending.set(false);
        flushScheduled.set(false);
        scheduler.set(Executors.newSingleThreadScheduledExecutor());
    }

    // The flush thread is gone, so insert what is left from here, a batch at a time, within the shutdown timeout.
    private void drainOnStop() {
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        try {
            while (!logQueue.isEmpty()) {
                List<ILoggingEvent> batch = new ArrayList<>(batchSize);
                logQueue.drainTo(batch, batchSize);
                insert(batch).block(Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
            }
        } catch (RuntimeException e) {
            log.warn("Failed to flush user-facing logs on shutdown, dropping '{}'", logQueue.size(), e);
        }
    }

    private void awaitTermination() {
        try {
            if (!scheduler.get().awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
                scheduler.get().shutdownNow();
                if (!scheduler.get().awaitTermination(shutdownTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
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
