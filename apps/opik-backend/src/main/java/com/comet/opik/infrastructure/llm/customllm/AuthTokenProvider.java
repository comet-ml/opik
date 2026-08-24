package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.api.ProviderAuthConfig;
import com.comet.opik.infrastructure.EncryptionUtils;
import com.comet.opik.infrastructure.LlmProviderTokenAuthConfig;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardException;
import com.comet.opik.infrastructure.redis.StringRedisClient;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.LongHistogram;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.Builder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.defaultIfBlank;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;

/**
 * Executes a custom provider's {@link ProviderAuthConfig} recipe and manages the resulting
 * short-lived bearer's lifecycle.
 *
 * <p>Tokens are cached in Redis — one bucket per provider, AES-GCM-encrypted, shared by every
 * backend pod — so a deployment makes roughly one fetch per refresh window regardless of pod count.
 * The cache key includes a hash of the recipe: any config edit is an instant deployment-wide cache
 * miss, no invalidation signal needed. A token is refreshed lazily, on the request path, once its
 * remaining lifetime drops inside the proportional refresh window. Cold-cache bursts collapse into
 * a single fetch via the distributed lock; on any Redis failure the provider degrades to a direct
 * fetch rather than failing the LLM call.
 *
 * <p>Every error message thrown from here is user-facing by contract: upstream status and body are
 * surfaced (never a generic "auth failed"), with credential values redacted.
 */
@Singleton
@Slf4j
public class AuthTokenProvider {

    record FetchedToken(String token, long ttlSeconds) {
    }

    private static final String CACHE_KEY_FORMAT = "llm_auth_token:%s:%s";
    private static final String DEFAULT_TOKEN_FIELD = "access_token";
    private static final String DEFAULT_EXPIRES_FIELD = "expires_in";
    private static final String CLIENT_ID_KEY = "client_id";
    private static final String CLIENT_SECRET_KEY = "client_secret";
    private static final String REDACTED = "***";
    private static final int ERROR_BODY_SNIPPET_CHARS = 500;
    // Upper bound on any token lifetime (1 year): beyond this a reply is malformed or malicious
    static final long MAX_TTL_SECONDS = 31_536_000L;

    private static final AttributeKey<String> OUTCOME = AttributeKey.stringKey("outcome");
    private static final AttributeKey<String> WORKSPACE_ID = AttributeKey.stringKey("workspace_id");
    private static final AttributeKey<String> PROVIDER_ID = AttributeKey.stringKey("provider_id");
    private static final AttributeKey<String> ORIGIN = AttributeKey.stringKey("origin");
    private static final String ORIGIN_REQUEST = "request";
    private static final String ORIGIN_TEST = "test";

    private final @NonNull StringRedisClient redisClient;
    private final @NonNull LockService lockService;
    private final @NonNull LlmProviderTokenAuthConfig config;
    private final DestinationGuard destinationGuard;
    private final HttpClient httpClient;
    private final LongCounter tokenRequests;
    private final LongHistogram fetchDurationMs;

    @Inject
    public AuthTokenProvider(@NonNull StringRedisClient redisClient, @NonNull LockService lockService,
            @NonNull @Config("llmProviderTokenAuth") LlmProviderTokenAuthConfig config) {
        this.redisClient = redisClient;
        this.lockService = lockService;
        this.config = config;
        this.destinationGuard = new DestinationGuard(config.getDestinationGuard());
        // Same posture as the LLM clients: HTTP/1.1 and no redirect following (a token endpoint
        // redirecting elsewhere is a misconfiguration or an attack, never a flow to honor).
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofMillis(config.getFetchTimeout().toMilliseconds()))
                .build();
        Meter meter = GlobalOpenTelemetry.get().getMeter("opik.llm_provider_token_auth");
        this.tokenRequests = meter.counterBuilder("llm_provider_token_requests")
                .setDescription("Bearer requests against the token cache, by outcome")
                .build();
        this.fetchDurationMs = meter.histogramBuilder("llm_provider_token_fetch_duration_ms")
                .setDescription("Duration of HTTP fetches against customers' auth services")
                .setUnit("ms")
                .ofLongs()
                .build();
    }

    /**
     * Returns a bearer for the given recipe, fetching or refreshing through the shared cache as
     * needed. Blocking — meant to be called from the LLM client's request path.
     *
     * @throws AuthTokenException with a redacted, user-facing message when the fetch fails
     */
    public String bearer(@NonNull String workspaceId, @NonNull UUID providerId,
            @NonNull ProviderAuthConfig authConfig) {
        String cacheKey = cacheKey(providerId, authConfig);
        String cached = readCache(cacheKey);
        if (cached != null) {
            recordRequestMetric(workspaceId, providerId, "cache_hit");
            return cached;
        }

        try {
            String token = lockService
                    .executeWithLockCustomExpire(
                            new LockService.Lock(cacheKey + ":fetch-lock"),
                            Mono.fromCallable(() -> refreshUnderLock(cacheKey, authConfig)),
                            Duration.ofMillis(config.getLockTimeout().toMilliseconds()))
                    .block(Duration.ofMillis(
                            config.getLockTimeout().toMilliseconds() + config.getFetchTimeout().toMilliseconds()));
            if (token != null) {
                recordRequestMetric(workspaceId, providerId, "fetched");
                return token;
            }
            // Empty result = we gave up waiting for the lock holder, who has likely written the
            // token by now — re-read before fetching. Distinct metric so a rising rate can flag
            // a mistuned lockTimeout. Known gap: if the holder's fetch failed, waiters still
            // fall through and fetch directly.
            String refreshed = readCache(cacheKey);
            if (refreshed != null) {
                recordRequestMetric(workspaceId, providerId, "lock_timeout_recovered");
                return refreshed;
            }
            log.warn("Timed out waiting for the token fetch lock for provider '{}'; fetching directly", providerId);
        } catch (AuthTokenException exception) {
            recordRequestMetric(workspaceId, providerId, "failed");
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("Token cache unavailable for provider '{}'; falling back to a direct fetch", providerId,
                    exception);
        }

        String token = fetchToken(authConfig).token();
        recordRequestMetric(workspaceId, providerId, "degraded_direct");
        return token;
    }

    /**
     * The LLM gateway rejected the current bearer (401, or 403 with a token-rejection hint):
     * records the "is this integration broken" signal, then drops the cached token so a revocation
     * discovered by one pod is seen by all of them at once. The drop is best-effort: a Redis
     * failure here only delays the cleanup until the bucket's own expiry.
     */
    public void invalidateAfterGatewayRejection(@NonNull String workspaceId, @NonNull UUID providerId,
            @NonNull ProviderAuthConfig authConfig) {
        recordRequestMetric(workspaceId, providerId, "gateway_rejected");
        String cacheKey = cacheKey(providerId, authConfig);
        try {
            redisClient.getBucket(cacheKey).delete();
        } catch (RuntimeException exception) {
            log.warn("Failed to invalidate cached token for provider '{}'", providerId, exception);
        }
    }

    private String refreshUnderLock(String cacheKey, ProviderAuthConfig authConfig) {
        // another pod may have refreshed while this one waited on the lock
        String cached = readCache(cacheKey);
        if (cached != null) {
            return cached;
        }
        FetchedToken fetched = fetchToken(authConfig);
        writeCache(cacheKey, fetched);
        return fetched.token();
    }

    private String readCache(String cacheKey) {
        try {
            String encrypted = redisClient.getBucket(cacheKey).get();
            return encrypted == null ? null : EncryptionUtils.decryptGcm(encrypted);
        } catch (RuntimeException exception) {
            log.warn("Failed to read the cached token for key '{}': {}", cacheKey, exception.getMessage());
            return null;
        }
    }

    /**
     * The bucket's own TTL is the refresh point ({@code lifetime * (1 - refreshFraction)}), so
     * freshness is simply key existence and Redis's clock is the single source of truth.
     */
    private void writeCache(String cacheKey, FetchedToken fetched) {
        long refreshPointMs = (long) (fetched.ttlSeconds() * 1_000 * (1 - config.getRefreshFraction()));
        // a resolved lifetime of 0 (the fallback for a reply that states none) means: don't cache
        if (refreshPointMs <= 0) {
            return;
        }
        try {
            redisClient.getBucket(cacheKey)
                    .set(EncryptionUtils.encryptGcm(fetched.token()), Duration.ofMillis(refreshPointMs));
        } catch (RuntimeException exception) {
            log.warn("Failed to cache the token for key '{}'; the token is still returned", cacheKey, exception);
        }
    }

    private String cacheKey(UUID providerId, ProviderAuthConfig authConfig) {
        return CACHE_KEY_FORMAT.formatted(providerId, DigestUtils.sha256Hex(JsonUtils.writeValueAsString(authConfig)));
    }

    /**
     * Runs the recipe once for the test-connection endpoint. Returns the resolved token lifetime —
     * never the token itself.
     *
     * @throws AuthTokenException with a redacted, user-facing message when the fetch fails
     */
    public long testFetch(@NonNull ProviderAuthConfig authConfig) {
        return fetchToken(authConfig, ORIGIN_TEST).ttlSeconds();
    }

    // --- recipe execution ---

    FetchedToken fetchToken(@NonNull ProviderAuthConfig authConfig) {
        return fetchToken(authConfig, ORIGIN_REQUEST);
    }

    private FetchedToken fetchToken(ProviderAuthConfig authConfig, String origin) {
        long startNanos = System.nanoTime();
        try {
            destinationGuard.validate(authConfig.tokenUrl());
        } catch (DestinationGuardException exception) {
            recordFetchMetric(startNanos, "destination_refused", origin);
            throw new AuthTokenException(exception.getMessage()
                    + " (self-hosted deployments with internal auth services can set LLM_PROVIDER_TOKEN_AUTH_DESTINATION_GUARD=relaxed)",
                    exception);
        }
        HttpRequest request = buildRequest(authConfig);
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException exception) {
            recordFetchMetric(startNanos, "unreachable", origin);
            throw new AuthTokenException("token fetch failed: could not reach '%s': %s"
                    .formatted(authConfig.tokenUrl(), redact(authConfig, exception.getMessage())), exception);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            recordFetchMetric(startNanos, "interrupted", origin);
            throw new AuthTokenException("token fetch was interrupted", exception);
        }

        String body = Optional.ofNullable(response.body()).orElse("");
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            recordFetchMetric(startNanos, "upstream_error", origin);
            throw new AuthTokenException("token fetch failed with status '%d' from '%s': %s"
                    .formatted(response.statusCode(), authConfig.tokenUrl(), redact(authConfig, body)));
        }
        if (body.length() > config.getMaxResponseChars()) {
            recordFetchMetric(startNanos, "oversized_reply", origin);
            throw new AuthTokenException("token reply from '%s' exceeds the maximum accepted size"
                    .formatted(authConfig.tokenUrl()));
        }

        JsonNode root;
        try {
            root = JsonUtils.getJsonNodeFromString(body);
        } catch (UncheckedIOException exception) {
            recordFetchMetric(startNanos, "non_json_reply", origin);
            throw new AuthTokenException("token endpoint at '%s' returned a non-JSON reply (status '%d')"
                    .formatted(authConfig.tokenUrl(), response.statusCode()));
        }

        String tokenField = defaultIfBlank(authConfig.tokenField(), DEFAULT_TOKEN_FIELD);
        JsonNode tokenNode = atDotPath(root, tokenField);
        if (!tokenNode.isTextual() || isBlank(tokenNode.asText())) {
            recordFetchMetric(startNanos, "token_field_missing", origin);
            // field names are safe to surface; values never are
            throw new AuthTokenException("field '%s' not found in the token reply; top-level fields: %s"
                    .formatted(tokenField, root.properties().stream().map(Map.Entry::getKey).toList()));
        }

        long ttlSeconds = resolveTtlSeconds(root, authConfig, startNanos, origin);
        recordFetchMetric(startNanos, "success", origin);
        return new FetchedToken(tokenNode.asText(), ttlSeconds);
    }

    private HttpRequest buildRequest(ProviderAuthConfig authConfig) {
        List<ProviderAuthConfig.Credential> credentials = Optional.ofNullable(authConfig.credentials())
                .orElse(List.of());
        var sendAs = Optional.ofNullable(authConfig.sendAs()).orElse(ProviderAuthConfig.SendAs.FORM);

        Builder builder;
        try {
            builder = HttpRequest.newBuilder(URI.create(authConfig.tokenUrl()))
                    .timeout(Duration.ofMillis(config.getFetchTimeout().toMilliseconds()));
        } catch (IllegalArgumentException exception) {
            throw new AuthTokenException(
                    "token_url '%s' is not a usable http(s) URL".formatted(authConfig.tokenUrl()), exception);
        }

        switch (sendAs) {
            case FORM -> builder
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(formEncode(credentials)));
            case JSON -> builder
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonEncode(credentials)));
            case BASIC -> {
                // OAuth2-standard mixed mode: client id/secret in the Basic header, every
                // remaining field in the form body
                String clientId = credentialValue(credentials, CLIENT_ID_KEY);
                String clientSecret = credentialValue(credentials, CLIENT_SECRET_KEY);
                if (clientId == null || clientSecret == null) {
                    throw new AuthTokenException(
                            "requires '%s' and '%s' credentials".formatted(CLIENT_ID_KEY,
                                    CLIENT_SECRET_KEY));
                }
                var bodyCredentials = credentials.stream()
                        .filter(credential -> !CLIENT_ID_KEY.equals(credential.key())
                                && !CLIENT_SECRET_KEY.equals(credential.key()))
                        .toList();
                builder.header("Authorization", "Basic " + Base64.getEncoder().encodeToString(
                        (clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8)))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(formEncode(bodyCredentials)));
            }
        }
        return builder.build();
    }

    /**
     * Resolves the token lifetime, in order: the reply's lifetime field (number or numeric string;
     * a non-positive value is an error), else the recipe's fallback (where 0 means the token is
     * served uncached), else an error naming the missing field.
     */
    private long resolveTtlSeconds(JsonNode root, ProviderAuthConfig authConfig, long startNanos, String origin) {
        String expiresField = defaultIfBlank(authConfig.expiresField(), DEFAULT_EXPIRES_FIELD);
        JsonNode expiresNode = atDotPath(root, expiresField);

        Long ttlSeconds = null;
        if (expiresNode.isNumber()) {
            ttlSeconds = expiresNode.longValue();
        } else if (expiresNode.isTextual() && isNotBlank(expiresNode.asText())) {
            try {
                ttlSeconds = Long.parseLong(expiresNode.asText().trim());
            } catch (NumberFormatException ignored) {
                // fall through to the fallback
            }
        }
        if (ttlSeconds != null) {
            if (ttlSeconds <= 0 || ttlSeconds > MAX_TTL_SECONDS) {
                recordFetchMetric(startNanos, "lifetime_invalid", origin);
                throw new AuthTokenException(
                        "token reply states a lifetime of '%d' seconds, outside the accepted range of 1 to %d"
                                .formatted(ttlSeconds, MAX_TTL_SECONDS));
            }
            return ttlSeconds;
        }
        // the configured fallback may legitimately be 0: the fetch-per-call convention
        if (authConfig.fallbackTtlSeconds() == null) {
            recordFetchMetric(startNanos, "lifetime_missing", origin);
            throw new AuthTokenException(
                    "token reply has no lifetime field '%s' and no fallback lifetime is configured"
                            .formatted(expiresField));
        }
        return authConfig.fallbackTtlSeconds();
    }

    private static String credentialValue(List<ProviderAuthConfig.Credential> credentials, String key) {
        return credentials.stream()
                .filter(credential -> key.equals(credential.key()))
                .map(ProviderAuthConfig.Credential::value)
                .filter(StringUtils::isNotBlank)
                .findFirst()
                .orElse(null);
    }

    private static String formEncode(List<ProviderAuthConfig.Credential> credentials) {
        return credentials.stream()
                .map(credential -> URLEncoder.encode(credential.key(), StandardCharsets.UTF_8) + "="
                        + URLEncoder.encode(Optional.ofNullable(credential.value()).orElse(""),
                                StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }

    private static String jsonEncode(List<ProviderAuthConfig.Credential> credentials) {
        var node = JsonUtils.createObjectNode();
        credentials.forEach(credential -> node.put(credential.key(), credential.value()));
        return node.toString();
    }

    private static JsonNode atDotPath(JsonNode root, String dotPath) {
        JsonNode current = root;
        for (String part : dotPath.split("\\.")) {
            current = current.path(part);
        }
        return current;
    }

    /**
     * Scrubs every credential value out of text destined for error messages and truncates it.
     * Applied to upstream bodies and transport errors alike — the one choke point that lets the
     * rest of the class surface upstream errors verbatim.
     */
    private static String redact(ProviderAuthConfig authConfig, String text) {
        if (isBlank(text)) {
            return "";
        }
        String result = text.length() > ERROR_BODY_SNIPPET_CHARS
                ? text.substring(0, ERROR_BODY_SNIPPET_CHARS) + "…"
                : text;
        for (var credential : Optional.ofNullable(authConfig.credentials())
                .orElse(List.<ProviderAuthConfig.Credential>of())) {
            if (isNotBlank(credential.value())) {
                result = result.replace(credential.value(), REDACTED);
            }
        }
        return result;
    }

    private void recordRequestMetric(String workspaceId, UUID providerId, String outcome) {
        tokenRequests.add(1, Attributes.of(
                OUTCOME, outcome, WORKSPACE_ID, workspaceId, PROVIDER_ID, providerId.toString()));
    }

    private void recordFetchMetric(long startNanos, String outcome, String origin) {
        fetchDurationMs.record((System.nanoTime() - startNanos) / 1_000_000,
                Attributes.of(OUTCOME, outcome, ORIGIN, origin));
    }
}
