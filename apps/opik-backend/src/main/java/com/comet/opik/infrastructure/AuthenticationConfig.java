package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.concurrent.TimeUnit;

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
     * successful auth calls: p99.99 is 1.15s, so 3s is ~2.6x and normal calls never reach it.
     * Set to 0 to inherit the shared client timeout.
     * <p>
     * Bounds a single <em>attempt</em>, not the operation: with retries the worst case is
     * {@code (requestMaxRetries + 1) * requestTimeout} plus backoff. Set {@link #requestMaxRetries}
     * to 0 for a hard sub-timeout ceiling.
     * <p>
     * The 30s ceiling mirrors the shipped jerseyClient.timeout as a literal, because jerseyClient
     * is bound on a different configuration class. This is an override, not a clamp: lowering
     * jerseyClient.timeout does not cap this hop.
     */
    @Valid @NotNull @JsonProperty
    @MinDuration(value = 0, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 30, unit = TimeUnit.SECONDS)
    private Duration requestTimeout;

    /**
     * Maximum number of <em>retries</em> for a failed auth request: additional attempts after the
     * first, not a total attempt budget. The shipped 1 therefore permits 2 outbound calls.
     * <p>
     * Retried: transport failures per {@code RetryUtils.handleHttpErrors}, plus HTTP 503/504, which
     * {@code RetriableHttpClient} maps to {@code RetryUtils.RetryableHttpException} -- React emits
     * 503s while draining during a rolling restart. Not retried: any other HTTP status, since
     * {@code verifyResponse} maps 4xx to {@code ClientErrorException}.
     * <p>
     * Capped at 5: each retry re-issues a call against a React service that may already be
     * CPU-starved, and the brownouts observed in production last 1-3 minutes, so retries cannot
     * recover one. Set to 0 to disable retries.
     */
    @JsonProperty
    @Min(0) @Max(5) private int requestMaxRetries;

    /**
     * Minimum backoff between auth request attempts. Non-zero deliberately: an immediate retry adds
     * load to a React service that may already be CPU-starved.
     */
    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 10, unit = TimeUnit.SECONDS)
    private Duration requestRetryMinBackoff;

    /**
     * Upper bound on the backoff between auth request attempts. Reactor's {@code Retry.backoff}
     * grows exponentially from {@link #requestRetryMinBackoff} and is capped here.
     */
    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 30, unit = TimeUnit.SECONDS)
    private Duration requestRetryMaxBackoff;

    /**
     * Cross-field constraint: a backoff whose minimum exceeds its maximum is contradictory, and
     * Reactor's {@code Retry.backoff} would clamp it silently rather than fail. Validate it at
     * startup so a bad config is a boot failure with a clear message, not a runtime surprise.
     */
    @JsonIgnore
    @AssertTrue(message = "authentication.requestRetryMinBackoff must not exceed authentication.requestRetryMaxBackoff") public boolean isRetryBackoffRangeValid() {
        return requestRetryMinBackoff == null
                || requestRetryMaxBackoff == null
                || requestRetryMinBackoff.toMilliseconds() <= requestRetryMaxBackoff.toMilliseconds();
    }
}
