package com.comet.opik.domain;

import lombok.NonNull;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiFunction;
import java.util.function.Supplier;

/**
 * Reads a free-form query's {@code system.query_log} entries for the post-run check, flushing only when they are not
 * there yet. It queries first: a flush that already happened after the query finished, the server's own or another
 * request's, has written its entries. Only when the initial entry is missing does it flag a flush with the
 * {@link FreeFormSqlQueryLogFlusher}, at most one per second across requests, and query again, up to
 * {@code maxAttempts} flushes. One flush per second is the worst case, not the rule.
 *
 * <p>The initial entry being there proves the rest is only when the query read nothing remotely: with
 * {@code prefer_localhost_replica = 1} a single-shard read runs on the initiator and has no other entry. A query with
 * remote shard reads always goes through a flush that started after it, the only proof every shard's entry is
 * written.
 */
class FreeFormSqlQueryLogReader {

    /** Queries the log, as (query id, account user); never flushes. */
    private final BiFunction<String, String, CompletableFuture<List<FreeFormSqlQueryLogEntry>>> fetch;
    private final Supplier<CompletableFuture<Void>> awaitFlush;
    private final int maxAttempts;

    FreeFormSqlQueryLogReader(
            @NonNull BiFunction<String, String, CompletableFuture<List<FreeFormSqlQueryLogEntry>>> fetch,
            @NonNull Supplier<CompletableFuture<Void>> awaitFlush, int maxAttempts) {
        this.fetch = fetch;
        this.awaitFlush = awaitFlush;
        this.maxAttempts = maxAttempts;
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
                : flushThenFetch(queryId, user, 1));
    }

    private CompletableFuture<List<FreeFormSqlQueryLogEntry>> flushThenFetch(String queryId, String user,
            int attempt) {
        return awaitFlush.get()
                .thenCompose(flushed -> fetch.apply(queryId, user))
                .thenCompose(entries -> hasInitial(entries, user) || attempt >= maxAttempts
                        ? CompletableFuture.completedFuture(entries)
                        : flushThenFetch(queryId, user, attempt + 1));
    }

    private static boolean hasInitial(List<FreeFormSqlQueryLogEntry> entries, String user) {
        return entries.stream().anyMatch(entry -> entry.initial() && entry.user().equals(user));
    }
}
