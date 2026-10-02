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
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Reads a free-form query's {@code system.query_log} entries for the post-run check, flushing only when they are not
 * there yet. It queries first: a flush that already happened after the query finished, the server's own or another
 * request's, has written its entries. Only when the initial entry is missing does it wait {@code retryDelayMillis},
 * flag a flush with the {@link FreeFormSqlQueryLogFlusher}, at most one per {@code minFlushInterval} across the cluster, and query
 * again, up to {@code maxFlushAttempts} flushes. The timings are in {@link FreeFormSqlPostRunCheckConfig}. The wait gives a flush already on its way, the server's or another
 * request's, the chance to land first. One flush per interval is the worst case, not the rule. The reader owns the
 * flusher and its cluster-wide permit; the DAO only runs the two statements.
 *
 * <p>The initial entry being there proves the rest is only when the query read nothing remotely: with
 * {@code prefer_localhost_replica = 1} a single-shard read runs on the initiator and has no other entry. A query with
 * remote shard reads always goes through a flush that started after it, the only proof every shard's entry is
 * written.
 */
@Singleton
class FreeFormSqlQueryLogReader {

    private static final String FLUSH_BUCKET = "free-form-sql-query-log-flush";

    /** Queries the log, as (query id, account user); never flushes. */
    private final BiFunction<String, String, CompletableFuture<List<FreeFormSqlQueryLogEntry>>> fetch;
    private final Supplier<CompletableFuture<Void>> awaitFlush;
    private final int maxAttempts;
    private final long retryDelayMillis;
    private final FreeFormSqlQueryLogFlusher.Scheduler scheduler;

    @Inject
    FreeFormSqlQueryLogReader(@NonNull FreeFormSqlQueryDAO dao, @NonNull RateLimitService rateLimitService,
            @NonNull @Config("freeFormSqlPostRunCheck") FreeFormSqlPostRunCheckConfig config) {
        this(dao::fetchQueryLog, flusher(dao, rateLimitService, config)::awaitFlush, config.getMaxFlushAttempts(),
                config.getLogRetryDelay().toMilliseconds(), FreeFormSqlQueryLogFlusher.Scheduler.DELAYED);
    }

    /** The cluster-wide permit allows one flush per {@code minFlushInterval} across every backend instance. */
    private static FreeFormSqlQueryLogFlusher flusher(FreeFormSqlQueryDAO dao, RateLimitService rateLimitService,
            FreeFormSqlPostRunCheckConfig config) {
        var permits = new RateLimitConfig.LimitConfig("unused", FLUSH_BUCKET, 1,
                config.getMinFlushInterval().toSeconds(), "");
        return new FreeFormSqlQueryLogFlusher(dao::flushQueryLog, config.getMinFlushInterval().toMilliseconds(),
                config.getFlushPermitRetry().toMilliseconds(), System::currentTimeMillis,
                FreeFormSqlQueryLogFlusher.Scheduler.DELAYED,
                () -> rateLimitService.isLimitExceeded(1, FLUSH_BUCKET, permits)
                        .map(exceeded -> !exceeded)
                        .toFuture());
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
