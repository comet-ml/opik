package com.comet.opik.domain;

import com.comet.opik.infrastructure.RateLimitConfig;
import com.comet.opik.infrastructure.ratelimit.RateLimitService;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Reads a free-form query's {@code system.query_log} entries for the post-run check, flushing only when they are not
 * there yet. It queries first: a flush that already happened after the query finished, the server's own or another
 * request's, has written its entries. Only when the initial entry is missing does it wait {@code retryDelayMillis},
 * flag a flush with the {@link FreeFormSqlQueryLogFlusher}, at most one per second across the cluster, and query
 * again, up to {@code maxAttempts} flushes. The wait gives a flush already on its way, the server's or another
 * request's, the chance to land first. One flush per second is the worst case, not the rule. The reader owns the
 * flusher and its cluster-wide permit; the DAO only runs the two statements.
 *
 * <p>The initial entry being there proves the rest is only when the query read nothing remotely: with
 * {@code prefer_localhost_replica = 1} a single-shard read runs on the initiator and has no other entry. A query with
 * remote shard reads always goes through a flush that started after it, the only proof every shard's entry is
 * written.
 */
@Singleton
class FreeFormSqlQueryLogReader {

    private static final long MIN_FLUSH_INTERVAL_MILLIS = 1_000;
    /** How soon a request whose flush was denied the cluster-wide permit asks for it again. */
    private static final long FLUSH_PERMIT_RETRY_MILLIS = 200;
    /** The cluster-wide flush budget: one per second, across every backend instance. */
    private static final RateLimitConfig.LimitConfig FLUSH_PERMITS = new RateLimitConfig.LimitConfig(
            "unused", "free-form-sql-query-log-flush", 1, 1, "");
    /** The wait between a read that misses the query's entry and the next attempt. */
    private static final long LOG_RETRY_DELAY_MILLIS = 500;
    /** Flushes tried before the check fails closed: the first that started after the query should be enough. */
    private static final int MAX_FLUSH_ATTEMPTS = 3;

    /** Queries the log, as (query id, account user); never flushes. */
    private final BiFunction<String, String, CompletableFuture<List<FreeFormSqlQueryLogEntry>>> fetch;
    private final Supplier<CompletableFuture<Void>> awaitFlush;
    private final int maxAttempts;
    private final long retryDelayMillis;
    private final FreeFormSqlQueryLogFlusher.Scheduler scheduler;

    @Inject
    FreeFormSqlQueryLogReader(@NonNull FreeFormSqlQueryDAO dao, @NonNull RateLimitService rateLimitService) {
        this(dao::fetchQueryLog, new FreeFormSqlQueryLogFlusher(dao::flushQueryLog, MIN_FLUSH_INTERVAL_MILLIS,
                FLUSH_PERMIT_RETRY_MILLIS, System::currentTimeMillis, FreeFormSqlQueryLogFlusher.Scheduler.DELAYED,
                () -> rateLimitService.isLimitExceeded(1, FLUSH_PERMITS.userFacingBucketName(), FLUSH_PERMITS)
                        .map(exceeded -> !exceeded)
                        .toFuture())::awaitFlush,
                MAX_FLUSH_ATTEMPTS, LOG_RETRY_DELAY_MILLIS, FreeFormSqlQueryLogFlusher.Scheduler.DELAYED);
    }

    FreeFormSqlQueryLogReader(
            @NonNull BiFunction<String, String, CompletableFuture<List<FreeFormSqlQueryLogEntry>>> fetch,
            @NonNull Supplier<CompletableFuture<Void>> awaitFlush, int maxAttempts, long retryDelayMillis,
            @NonNull FreeFormSqlQueryLogFlusher.Scheduler scheduler) {
        this.fetch = fetch;
        this.awaitFlush = awaitFlush;
        this.maxAttempts = maxAttempts;
        this.retryDelayMillis = retryDelayMillis;
        this.scheduler = scheduler;
    }

    /**
     * @return the entries of {@code queryId}; without the initial one as {@code user} if it never appeared, which the
     *     check then rejects
     */
    CompletableFuture<List<FreeFormSqlQueryLogEntry>> entries(@NonNull String queryId, @NonNull String user,
            boolean readsRemotely) {
        if (readsRemotely) {
            return flushThenFetch(queryId, user, 1);
        }
        return fetch.apply(queryId, user).thenCompose(entries -> hasInitial(entries, user)
                ? CompletableFuture.completedFuture(entries)
                : afterRetryDelay().thenCompose(waited -> flushThenFetch(queryId, user, 1)));
    }

    private CompletableFuture<List<FreeFormSqlQueryLogEntry>> flushThenFetch(String queryId, String user,
            int attempt) {
        return awaitFlush.get()
                .thenCompose(flushed -> fetch.apply(queryId, user))
                .thenCompose(entries -> hasInitial(entries, user) || attempt >= maxAttempts
                        ? CompletableFuture.completedFuture(entries)
                        : afterRetryDelay().thenCompose(waited -> flushThenFetch(queryId, user, attempt + 1)));
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
