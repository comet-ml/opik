package com.comet.opik.domain;

import com.comet.opik.infrastructure.FreeFormSqlPostRunCheckConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

/**
 * Reads a free-form query's {@code system.query_log} entries for the post-run check, never flushing the log. Each read
 * waits {@code logReadDelay} first: ClickHouse flushes the log on every node on its own interval, so once that has
 * passed every entry of the query, initial and shard-side, is written. It reads up to {@code maxLogReadAttempts}
 * times while the initial entry is missing. The settings are in {@link FreeFormSqlPostRunCheckConfig}.
 */
@Singleton
class FreeFormSqlQueryLogReader {

    /** Runs {@code task} after {@code delayMillis}; injected so tests control time. */
    interface Scheduler {
        void schedule(long delayMillis, Runnable task);

        Scheduler DELAYED = (delayMillis, task) -> CompletableFuture
                .delayedExecutor(delayMillis, TimeUnit.MILLISECONDS).execute(task);
    }

    /** Queries the log, as (query id, account user). */
    private final BiFunction<String, String, CompletableFuture<List<FreeFormSqlQueryLogEntry>>> fetch;
    private final int maxAttempts;
    private final long delayMillis;
    private final Scheduler scheduler;

    @Inject
    FreeFormSqlQueryLogReader(@NonNull FreeFormSqlQueryDAO dao,
            @NonNull @Config("freeFormSqlPostRunCheck") FreeFormSqlPostRunCheckConfig config) {
        this(dao::fetchQueryLog, config.getMaxLogReadAttempts(), config.getLogReadDelay().toMilliseconds(),
                Scheduler.DELAYED);
    }

    FreeFormSqlQueryLogReader(
            @NonNull BiFunction<String, String, CompletableFuture<List<FreeFormSqlQueryLogEntry>>> fetch,
            int maxAttempts, long delayMillis, @NonNull Scheduler scheduler) {
        this.fetch = fetch;
        this.maxAttempts = maxAttempts;
        this.delayMillis = delayMillis;
        this.scheduler = scheduler;
    }

    /**
     * @return the entries of {@code queryId}; without the initial one as {@code user} if it never appeared, which the
     *     check then reports
     */
    CompletableFuture<List<FreeFormSqlQueryLogEntry>> entries(@NonNull String queryId, @NonNull String user) {
        return read(queryId, user, 1);
    }

    private CompletableFuture<List<FreeFormSqlQueryLogEntry>> read(String queryId, String user, int attempt) {
        var waited = new CompletableFuture<Void>();
        scheduler.schedule(delayMillis, () -> waited.complete(null));
        return waited.thenCompose(ready -> fetch.apply(queryId, user))
                .thenCompose(entries -> hasInitial(entries, user) || attempt >= maxAttempts
                        ? CompletableFuture.completedFuture(entries)
                        : read(queryId, user, attempt + 1));
    }

    private static boolean hasInitial(List<FreeFormSqlQueryLogEntry> entries, String user) {
        return entries.stream().anyMatch(entry -> entry.initial() && entry.user().equals(user));
    }
}
