package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AuthenticationConfig {

    public record UrlConfig(@Valid @JsonProperty @NotNull String url) {
    }

    @Valid @JsonProperty
    private boolean enabled;

    @Valid @JsonProperty
    private int apiKeyResolutionCacheTTLInSec;

    @Valid @JsonProperty
    private UrlConfig reactService;

    /**
     * Per-call read timeout for auth requests to the React service, overriding the shared
     * jerseyClient timeout (30s) for this hop only. Sized from measured production latency of
     * successful auth calls: p50 22ms, p99 147ms, p99.9 305ms, p99.99 1.15s. 3s is ~2.6x p99.99,
     * so normal calls never reach it, while a stalled call fails in 3s instead of 30s.
     * Set to 0 to disable the override and inherit the shared client timeout.
     */
    @Valid @JsonProperty
    private Duration requestTimeout;

    /**
     * Maximum retry attempts for a failed auth request, using
     * {@link com.comet.opik.utils.RetryUtils#handleHttpErrors} so the retriable-exception set and
     * backoff behaviour match the rest of the codebase.
     * <p>
     * Note: React CPU brownouts observed in production last 1-3 minutes, so a retry will not
     * recover a request stalled by one of those - it recovers sub-second blips and 503s emitted
     * during graceful shutdown. The timeout above is what bounds user-visible latency.
     * Set to 0 to disable retries.
     */
    @Valid @JsonProperty
    private int requestMaxRetries;

    /**
     * Minimum backoff between auth request attempts. Deliberately non-zero: an immediate retry
     * adds load to a React service that may already be CPU-starved.
     */
    @Valid @JsonProperty
    private Duration requestRetryMinBackoff;

    /**
     * Upper bound on the exponential backoff between auth request attempts.
     */
    @Valid @JsonProperty
    private Duration requestRetryMaxBackoff;
}
