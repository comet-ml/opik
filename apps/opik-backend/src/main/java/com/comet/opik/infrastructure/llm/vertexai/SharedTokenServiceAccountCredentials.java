package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.infrastructure.llm.SharedTokenCache;
import com.comet.opik.infrastructure.llm.VertexAIClientConfig;
import com.comet.opik.utils.JsonUtils;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import lombok.NonNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service-account credentials whose OAuth token is fetched once for every pod and shared through
 * {@link SharedTokenCache}, rather than fetched by each pod for itself.
 *
 * <p>Between refreshes the token is held in memory like any {@link GoogleCredentials}, so a call doesn't reach Redis.
 * The token's own expiry travels with it, so every pod refreshes against the real expiry. The bucket is evicted at
 * least {@link #MIN_REFRESH_AHEAD} before that expiry, ahead of the auth library's own refresh margin: otherwise a pod
 * refreshing near the end would be handed back the same expiring token from Redis.
 *
 * <p>The fetch itself is bounded by {@link #FETCH_SHARE_OF_LEASE} of the lock's lease, which is not renewed: the auth
 * library's own timeouts and retries can run past it, and a holder still fetching after its lease would let a waiter
 * take the lock and fetch a second token.
 */
final class SharedTokenServiceAccountCredentials extends GoogleCredentials {

    static final Duration MIN_REFRESH_AHEAD = Duration.ofMinutes(5);

    /** The rest of the lease is left for writing the token to Redis. */
    static final double FETCH_SHARE_OF_LEASE = 0.8;

    // A fetch past its deadline is abandoned rather than waited for, and its thread ends at the library's own timeout.
    private static final Executor FETCHES = Executors.newVirtualThreadPerTaskExecutor();

    /**
     * The fetch still running for this key, if any. The next refresh waits on it again rather than starting another,
     * so a stalled token endpoint gets one request per key and pod, not one per abandoned deadline.
     */
    private final transient AtomicReference<CompletableFuture<AccessToken>> inFlight = new AtomicReference<>();

    private record CachedToken(@NonNull String token, long expiresAtEpochMillis) {
    }

    private final transient ServiceAccountCredentials delegate;
    private final transient SharedTokenCache tokenCache;
    private final String cacheKey;
    private final double refreshFraction;
    private final Duration lockTimeout;

    SharedTokenServiceAccountCredentials(@NonNull ServiceAccountCredentials delegate,
            @NonNull SharedTokenCache tokenCache, @NonNull String cacheKey, @NonNull VertexAIClientConfig config) {
        super(null, delegate.getQuotaProjectId());
        this.delegate = delegate;
        this.tokenCache = tokenCache;
        this.cacheKey = cacheKey;
        this.refreshFraction = config.tokenCacheRefreshFraction();
        this.lockTimeout = config.tokenCacheLockTimeout().toJavaDuration();
    }

    String projectId() {
        return delegate.getProjectId();
    }

    @Override
    public AccessToken refreshAccessToken() throws IOException {
        try {
            // The lease bounds the holder's fetch as well, so a waiter gives up after one lease to wait and one to fetch.
            var value = tokenCache.get(cacheKey, lockTimeout, lockTimeout, this::fetch).value();
            var cached = JsonUtils.treeToValue(JsonUtils.getJsonNodeFromString(value), CachedToken.class);
            return new AccessToken(cached.token(), new Date(cached.expiresAtEpochMillis()));
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private SharedTokenCache.Fetched fetch() {
        AccessToken token;
        try {
            token = fetchWithinLease();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var expiresAt = Objects.requireNonNull(token.getExpirationTime(), "Google token without an expiry").toInstant();
        var value = JsonUtils.valueToTree(new CachedToken(token.getTokenValue(), expiresAt.toEpochMilli())).toString();
        return new SharedTokenCache.Fetched(value, cacheFor(Duration.between(Instant.now(), expiresAt)));
    }

    private AccessToken fetchWithinLease() throws IOException {
        var deadline = Duration.ofMillis((long) (lockTimeout.toMillis() * FETCH_SHARE_OF_LEASE));
        var fetch = inFlight.updateAndGet(running -> running != null && !running.isDone()
                ? running
                : CompletableFuture.supplyAsync(() -> {
                    try {
                        return delegate.refreshAccessToken();
                    } catch (IOException exception) {
                        throw new UncheckedIOException(exception);
                    }
                }, FETCHES));
        try {
            return fetch.get(deadline.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            throw new IOException("Fetching the Vertex AI OAuth token took longer than '%s'".formatted(deadline),
                    exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while fetching the Vertex AI OAuth token", exception);
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof UncheckedIOException failure) {
                throw failure.getCause();
            }
            throw new IOException("Fetching the Vertex AI OAuth token failed", exception.getCause());
        }
    }

    private Duration cacheFor(Duration lifetime) {
        var byFraction = Duration.ofMillis((long) (lifetime.toMillis() * (1 - refreshFraction)));
        var latest = lifetime.minus(MIN_REFRESH_AHEAD);
        return byFraction.compareTo(latest) < 0 ? byFraction : latest;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SharedTokenServiceAccountCredentials that && cacheKey.equals(that.cacheKey);
    }

    @Override
    public int hashCode() {
        return cacheKey.hashCode();
    }
}
