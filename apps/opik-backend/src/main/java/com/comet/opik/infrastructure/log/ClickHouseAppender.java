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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
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
    private final @NonNull Duration insertTimeout;
    private final int batchSize;
    private volatile boolean running = true;

    private final BlockingQueue<ILoggingEvent> logQueue;
    // Set while an insert is in flight, so there is at most one at a time.
    private final AtomicBoolean insertInFlight = new AtomicBoolean();
    private volatile CompletableFuture<Void> lastInsert = CompletableFuture.completedFuture(null);
    // Set while a threshold-triggered flush is queued, so appends don't queue one each.
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private final AtomicLong dropped = new AtomicLong();

    private final AtomicReference<ScheduledExecutorService> scheduler;

    ClickHouseAppender(@NonNull UserLogTableFactory userLogTableFactory, @NonNull ClickHouseLogAppenderConfig config,
            @NonNull ScheduledExecutorService scheduler) {
        this.userLogTableFactory = userLogTableFactory;
        this.flushIntervalDuration = config.getFlushIntervalDuration();
        this.shutdownTimeout = config.getShutdownTimeout();
        this.insertTimeout = config.getInsertTimeout();
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
        if (!insertInFlight.compareAndSet(false, true)) {
            return;
        }

        List<ILoggingEvent> batch = new ArrayList<>(batchSize);
        logQueue.drainTo(batch, batchSize);

        if (batch.isEmpty()) {
            insertInFlight.set(false);
            return;
        }

        // The flag clears when the insert completes, not before: a slow ClickHouse backs up into the bounded queue.
        // lastInsert is set before subscribing and always completes normally, so stop() waits for this insert.
        var done = new CompletableFuture<Void>();
        lastInsert = done;
        insert(batch, insertTimeout)
                .doFinally(signal -> {
                    done.complete(null);
                    insertInFlight.set(false);
                    if (running && logQueue.size() >= batchSize) {
                        submit(this::flushLogs);
                    }
                })
                .subscribe();
    }

    // Never errors: a timeout is expected under load and logged as a warning, anything else as an error.
    private Mono<Void> insert(List<ILoggingEvent> batch, Duration timeout) {
        Map<String, List<ILoggingEvent>> eventsPerTable = batch.stream()
                .collect(groupingBy(event -> event.getMDCPropertyMap().getOrDefault(UserLog.MARKER, "")));

        return Mono.when(eventsPerTable.entrySet().stream()
                .map(entry -> save(entry.getKey(), entry.getValue()))
                .toList())
                .timeout(timeout)
                .onErrorResume(e -> {
                    if (e instanceof TimeoutException) {
                        log.warn("User-facing log insert timed out after '{}', up to '{}' logs lost", timeout,
                                batch.size());
                    } else {
                        log.error("Failed to insert logs", e);
                    }
                    return Mono.empty();
                });
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
        // One deadline for every shutdown step, so stop() stays within shutdownTimeout.
        long deadline = System.nanoTime() + shutdownTimeout.toNanos();
        scheduler.get().shutdown();
        awaitTermination(deadline);
        drainOnStop(deadline);
        reportDropped();
        logQueue.clear();
        insertInFlight.set(false);
        flushScheduled.set(false);
        scheduler.set(Executors.newSingleThreadScheduledExecutor());
    }

    // The flush thread is gone: wait for its last insert, so there is still one at a time, then insert what is
    // left from here, a batch at a time. A batch that times out doesn't end the drain; the deadline does.
    private void drainOnStop(long deadline) {
        try {
            lastInsert.get(remainingNanos(deadline), TimeUnit.NANOSECONDS);
            while (!logQueue.isEmpty() && remainingNanos(deadline) > 0) {
                List<ILoggingEvent> batch = new ArrayList<>(batchSize);
                logQueue.drainTo(batch, batchSize);
                insert(batch, Duration.ofNanos(Math.min(insertTimeout.toNanos(), remainingNanos(deadline)))).block();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted flushing user-facing logs on shutdown, dropping '{}'", logQueue.size());
            return;
        } catch (ExecutionException | TimeoutException e) {
            // The last insert outlived the shutdown timeout; what is still queued is dropped below.
        }

        if (!logQueue.isEmpty()) {
            log.warn("Shutdown timeout reached, dropping '{}' user-facing logs", logQueue.size());
        }
    }

    private static long remainingNanos(long deadline) {
        return Math.max(0, deadline - System.nanoTime());
    }

    private void awaitTermination(long deadline) {
        try {
            if (!scheduler.get().awaitTermination(remainingNanos(deadline), TimeUnit.NANOSECONDS)) {
                scheduler.get().shutdownNow();
                if (!scheduler.get().awaitTermination(remainingNanos(deadline), TimeUnit.NANOSECONDS)) {
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
