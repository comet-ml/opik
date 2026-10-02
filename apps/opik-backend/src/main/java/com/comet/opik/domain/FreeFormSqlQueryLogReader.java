package com.comet.opik.domain;

import com.comet.opik.infrastructure.FreeFormSqlPostRunCheckConfig;
import com.comet.opik.infrastructure.RateLimitConfig;
import com.comet.opik.infrastructure.ratelimit.RateLimitService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Reads a free-form query's {@code system.query_log} entries for the post-run check, flushing only when they are not
 * there yet. It queries first: a flush that already happened after the query finished, the server's own or another
 * request's, has written its entries. Only when the initial entry is missing does it wait, query again, and if it is
 * still missing ask for a flush, up to {@code maxFlushAttempts} times, waiting between attempts.
 *
 * <p>Flushes are limited by a Redis rate limiter alone: one permit per {@code minFlushInterval} across every backend
 * instance, so one flush per interval is the worst case, not the rule. A request denied the permit knows some request took it within the
 * interval, so that request's flush has run or is running, and it only reads the log again. The timings are in
 * {@link FreeFormSqlPostRunCheckConfig}.
 *
 * <p>The initial entry being there proves the rest is only when the query read nothing remotely: with
 * {@code prefer_localhost_replica = 1} a single-shard read runs on the initiator and has no other entry. A query with
 * remote shard reads skips the first read and goes straight to a flush attempt.
 */
@Singleton
class FreeFormSqlQueryLogReader {

    /** Runs {@code task} after {@code delayMillis}; injected so tests control time. */
    interface Scheduler {
        void schedule(long delayMillis, Runnable task);

        Scheduler DELAYED = (delayMillis, task) -> CompletableFuture
                .delayedExecutor(delayMillis, TimeUnit.MILLISECONDS).execute(task);
    }

    private static final String FLUSH_BUCKET = "free-form-sql-query-log-flush";

    /** Queries the log, as (query id, account user); never flushes. */
    private final BiFunction<String, String, CompletableFuture<List<FreeFormSqlQueryLogEntry>>> fetch;
    private final Supplier<CompletableFuture<Void>> flush;
    /** Whether this request may flush now, from the cluster-wide permit; an error means it cannot be told. */
    private final Supplier<CompletableFuture<Boolean>> permit;
    private final int maxFlushAttempts;
    private final long retryDelayMillis;
    private final Scheduler scheduler;

    @Inject
    FreeFormSqlQueryLogReader(@NonNull FreeFormSqlQueryDAO dao, @NonNull RateLimitService rateLimitService,
            @NonNull @Config("freeFormSqlPostRunCheck") FreeFormSqlPostRunCheckConfig config) {
        this(dao::fetchQueryLog, dao::flushQueryLog, permit(rateLimitService, FLUSH_BUCKET, config),
                config.getMaxFlushAttempts(), config.getLogRetryDelay().toMilliseconds(), Scheduler.DELAYED);
    }

    FreeFormSqlQueryLogReader(
            @NonNull BiFunction<String, String, CompletableFuture<List<FreeFormSqlQueryLogEntry>>> fetch,
            @NonNull Supplier<CompletableFuture<Void>> flush, @NonNull Supplier<CompletableFuture<Boolean>> permit,
            int maxFlushAttempts, long retryDelayMillis, @NonNull Scheduler scheduler) {
        this.fetch = fetch;
        this.flush = flush;
        this.permit = permit;
        this.maxFlushAttempts = maxFlushAttempts;
        this.retryDelayMillis = retryDelayMillis;
        this.scheduler = scheduler;
    }

    /** One permit per {@code minFlushInterval} in {@code bucket}, shared by every instance using the same Redis. */
    static Supplier<CompletableFuture<Boolean>> permit(RateLimitService rateLimitService, String bucket,
            FreeFormSqlPostRunCheckConfig config) {
        var permits = new RateLimitConfig.LimitConfig("unused", bucket, 1, config.getMinFlushInterval().toSeconds(),
                "");
        return () -> rateLimitService.isLimitExceeded(1, bucket, permits).map(exceeded -> !exceeded).toFuture();
    }

    /**
     * @return the entries of {@code queryId}; without the initial one as {@code user} if it never appeared, which the
     *     check then rejects
     */
    CompletableFuture<List<FreeFormSqlQueryLogEntry>> entries(@NonNull String queryId, @NonNull String user,
            boolean readsRemotely) {
        if (readsRemotely) {
            return attempt(queryId, user, 1);
        }
        // Read again after the wait before flushing: a flush that landed meanwhile has written the entry.
        return fetch.apply(queryId, user)
                .thenCompose(first -> hasInitial(first, user)
                        ? CompletableFuture.completedFuture(first)
                        : afterRetryDelay().thenCompose(waited -> fetch.apply(queryId, user)))
                .thenCompose(second -> hasInitial(second, user)
                        ? CompletableFuture.completedFuture(second)
                        : attempt(queryId, user, 1));
    }

    /**
     * Flushes if the permit allows. When denied, some request took this interval's permit, so its flush has run or is
     * running; this one only reads the log again.
     */
    private CompletableFuture<List<FreeFormSqlQueryLogEntry>> attempt(String queryId, String user, int attempt) {
        return permit.get()
                .thenCompose(granted -> granted ? flush.get() : CompletableFuture.<Void>completedFuture(null))
                .thenCompose(flushed -> fetch.apply(queryId, user))
                .thenCompose(entries -> hasInitial(entries, user) || attempt >= maxFlushAttempts
                        ? CompletableFuture.completedFuture(entries)
                        : afterRetryDelay().thenCompose(waited -> attempt(queryId, user, attempt + 1)));
    }

    private CompletableFuture<Void> afterRetryDelay() {
        var waited = new CompletableFuture<Void>();
        scheduler.schedule(retryDelayMillis, () -> waited.complete(null));
        return waited;
    }

    private static boolean hasInitial(List<FreeFormSqlQueryLogEntry> entries, String user) {
        return entries.stream().anyMatch(entry -> entry.initial() && entry.user().equals(user));
    }
}
