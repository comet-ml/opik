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

/**
 * Service-account credentials whose OAuth token is fetched once for every pod and shared through
 * {@link SharedTokenCache}, rather than fetched by each pod for itself.
 *
 * <p>Between refreshes the token is held in memory like any {@link GoogleCredentials}, so a call doesn't reach Redis.
 * The token's own expiry travels with it, so every pod refreshes against the real expiry. The bucket is evicted at
 * least {@code tokenCacheMinRefreshAhead} before that expiry, ahead of the auth library's own refresh margin: otherwise a pod
 * refreshing near the end would be handed back the same expiring token from Redis.
 *
 * <p>The fetch is bounded by {@link SharedTokenCache#FETCH_SHARE_OF_LEASE} of the lock's lease, which is not renewed,
 * and cancelled past it.
 */
final class SharedTokenServiceAccountCredentials extends GoogleCredentials {

    private record CachedToken(@NonNull String token, long expiresAtEpochMillis) {
    }

    private final transient ServiceAccountCredentials delegate;
    private final transient SharedTokenCache tokenCache;
    private final String cacheKey;
    private final double refreshFraction;
    private final Duration minRefreshAhead;
    private final Duration lockTimeout;

    SharedTokenServiceAccountCredentials(@NonNull ServiceAccountCredentials delegate,
            @NonNull SharedTokenCache tokenCache, @NonNull String cacheKey, @NonNull VertexAIClientConfig config) {
        super(null, delegate.getQuotaProjectId());
        this.delegate = delegate;
        this.tokenCache = tokenCache;
        this.cacheKey = cacheKey;
        this.refreshFraction = config.tokenCacheRefreshFraction();
        this.minRefreshAhead = config.tokenCacheMinRefreshAhead().toJavaDuration();
        this.lockTimeout = config.tokenCacheLockTimeout().toJavaDuration();
    }

    String projectId() {
        return delegate.getProjectId();
    }

    @Override
    public AccessToken refreshAccessToken() throws IOException {
        var fetchTimeout = Duration.ofMillis((long) (lockTimeout.toMillis() * SharedTokenCache.FETCH_SHARE_OF_LEASE));
        try {
            var value = tokenCache.get(cacheKey, lockTimeout, fetchTimeout, this::fetch).value();
            var cached = JsonUtils.treeToValue(JsonUtils.getJsonNodeFromString(value), CachedToken.class);
            return new AccessToken(cached.token(), new Date(cached.expiresAtEpochMillis()));
        } catch (SharedTokenCache.FetchTimeoutException exception) {
            throw new IOException("Fetching the Vertex AI OAuth token took longer than '%s'"
                    .formatted(exception.getTimeout()), exception);
        } catch (UncheckedIOException exception) {
            throw exception.getCause();
        }
    }

    private SharedTokenCache.Fetched fetch() {
        AccessToken token;
        try {
            token = delegate.refreshAccessToken();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
        var expiresAt = Objects.requireNonNull(token.getExpirationTime(), "Google token without an expiry").toInstant();
        var value = JsonUtils.valueToTree(new CachedToken(token.getTokenValue(), expiresAt.toEpochMilli())).toString();
        return new SharedTokenCache.Fetched(value, cacheFor(Duration.between(Instant.now(), expiresAt)));
    }

    private Duration cacheFor(Duration lifetime) {
        var byFraction = Duration.ofMillis((long) (lifetime.toMillis() * (1 - refreshFraction)));
        var latest = lifetime.minus(minRefreshAhead);
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
