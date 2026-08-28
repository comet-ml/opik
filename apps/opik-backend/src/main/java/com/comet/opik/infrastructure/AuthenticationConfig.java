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
     * successful auth calls: p50 22ms, p99 147ms, p99.9 305ms, p99.99 1.15s. 3s is ~2.6x p99.99,
     * so normal calls never reach it, while a stalled attempt fails in 3s instead of 30s.
     * Set to 0 to disable the override and inherit the shared client timeout.
     * <p>
     * This bounds a single <em>attempt</em>, not the whole operation. With retries enabled the
     * end-to-end worst case is {@code (requestMaxRetries + 1) * requestTimeout} plus the backoff
     * between attempts -- ~6.4s at the shipped values (2 attempts, 250ms backoff plus Reactor's
     * jitter). That is still
     * well under the 30s shared timeout it replaces, but callers that need a hard sub-3s ceiling
     * must set {@link #requestMaxRetries} to 0.
     */
    // No Java-side default: config.yml is the single source for these values, so there is nothing
    // to keep in sync. @NotNull turns a missing key into a boot failure naming the property rather
    // than a silent fallback. Note this means a configuration file must carry the `authentication:`
    // block; omitting it entirely fails validation, because OpikConfiguration cascades @Valid into
    // its default AuthenticationConfig instance.
    @Valid @NotNull @JsonProperty
    @MinDuration(value = 0, unit = TimeUnit.MILLISECONDS)
    // Bounded by the shipped jerseyClient.timeout (30s). Note this is an override, not a clamp:
    // ClientProperties.READ_TIMEOUT wins over the client's configured timeout in both directions,
    // so lowering jerseyClient.timeout below this value does NOT cap the auth hop -- this setting
    // has to be lowered too. The ceiling is a literal rather than a cross-object constraint
    // because jerseyClient is bound on a different configuration class; if that default ever
    // changes, this bound must change with it.
    @MaxDuration(value = 30, unit = TimeUnit.SECONDS)
    private Duration requestTimeout;

    /**
     * Maximum number of <em>retries</em> for a failed auth request: additional attempts after the
     * first, not a total attempt budget. The default of 1 therefore permits 2 outbound calls.
     * <p>
     * The call goes through the shared {@code RetriableHttpClient}, which owns retry and timeout
     * policy for outbound calls in this service, so nothing bespoke is implemented for this hop.
     * Retried: transport failures per {@code RetryUtils.handleHttpErrors}, plus HTTP 503/504, which
     * the client maps to {@code RetryUtils.RetryableHttpException} -- React emits 503s while
     * draining during a rolling restart. Not retried: any other HTTP error status, since
     * {@code verifyResponse} maps 4xx to {@code ClientErrorException}, which is not retriable.
     * <p>
     * Note: React CPU brownouts observed in production last 1-3 minutes, so a retry will not
     * recover a request stalled by one of those. The timeout is what bounds user-visible latency.
     * Set to 0 to disable retries.
     */
    @Valid @NotNull @JsonProperty
    @Min(0)
    // Capped deliberately: each retry re-issues an auth call against a React service that may
    // already be CPU-starved, and retries cannot recover a multi-minute brownout anyway.
    @Max(5) private Integer requestMaxRetries;

    /**
     * Minimum backoff between auth request attempts. Deliberately non-zero: an immediate retry
     * adds load to a React service that may already be CPU-starved.
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
