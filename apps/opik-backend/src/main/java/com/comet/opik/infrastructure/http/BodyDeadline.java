package com.comet.opik.infrastructure.http;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import lombok.NonNull;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Closes a response body once a deadline passes, so a body that trickles in can't outlast it: a read timeout only
 * bounds each wait for bytes. Closing a user destination's body cancels its connection, see
 * {@link UserDestinationHttpClient}, which ends a read blocked on it. Closing this before the deadline cancels it.
 */
public final class BodyDeadline implements AutoCloseable {

    /** One thread for every deadline; a cancelled deadline is removed at once, so none outlives its read. */
    private static final ScheduledThreadPoolExecutor SCHEDULER = newScheduler();

    /** Threads in {@link #CLOSERS}: closing aborts a connection, which is quick, so a few cover many deadlines. */
    private static final int CLOSER_THREADS = 4;

    /**
     * Runs the closes, so one that is slow never holds up the scheduler and the deadlines after it. Bounded in threads;
     * a close waits in its queue while all of them are busy.
     */
    private static final ThreadPoolExecutor CLOSERS = newClosers();

    private final AtomicBoolean expired = new AtomicBoolean();
    private final ScheduledFuture<?> closing;

    private BodyDeadline(Closeable body, Duration within) {
        this.closing = SCHEDULER.schedule(() -> {
            expired.set(true);
            CLOSERS.execute(() -> closeQuietly(body));
        }, Math.max(0, within.toNanos()), TimeUnit.NANOSECONDS);
    }

    /** Starts the deadline: {@code body} is closed once {@code within} has passed, unless this is closed first. */
    public static BodyDeadline closing(@NonNull Closeable body, @NonNull Duration within) {
        return new BodyDeadline(body, within);
    }

    /** Whether the deadline passed and the body was closed because of it. */
    public boolean expired() {
        return expired.get();
    }

    @Override
    public void close() {
        closing.cancel(false);
    }

    private static void closeQuietly(Closeable body) {
        try {
            body.close();
        } catch (IOException ignored) {
            // the read that is cut short reports the failure
        }
    }

    private static ThreadPoolExecutor newClosers() {
        var closers = new ThreadPoolExecutor(CLOSER_THREADS, CLOSER_THREADS, 60, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(), new ThreadFactoryBuilder()
                        .setNameFormat("http-body-close-%d")
                        .setDaemon(true)
                        .build());
        closers.allowCoreThreadTimeOut(true);
        return closers;
    }

    private static ScheduledThreadPoolExecutor newScheduler() {
        var scheduler = new ScheduledThreadPoolExecutor(1, new ThreadFactoryBuilder()
                .setNameFormat("http-body-deadline-%d")
                .setDaemon(true)
                .build());
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }
}
