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
     * between attempts -- ~6.25s at the shipped values (2 attempts, 250ms backoff). That is still
     * well under the 30s shared timeout it replaces, but callers that need a hard sub-3s ceiling
     * must set {@link #requestMaxRetries} to 0.
     */
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
     * Maximum retry attempts for a failed auth request. The retry is a plain synchronous loop in
     * {@code RemoteAuthService}, but it classifies failures with
     * {@link com.comet.opik.utils.RetryUtils#isRetriableException} -- the same predicate
     * {@code RetryUtils.handleHttpErrors} filters on -- so the retriable set matches the rest of
     * the codebase without building a Reactor chain around a blocking call.
     * <p>
     * That set is transport-level only: {@code SocketException}, {@code TimeoutException},
     * {@code InterruptedIOException} (covering {@code SocketTimeoutException} and
     * {@code ConnectTimeoutException}), {@code NoHttpResponseException},
     * {@code RetryUtils.RetryableHttpException}, and a {@code ProcessingException} wrapping any of
     * those. An HTTP error <em>status</em> is deliberately not retried: {@code verifyResponse} maps
     * 4xx/5xx to {@code ClientErrorException}/{@code InternalServerErrorException}, neither of which
     * is retriable, so a 503 surfaces to the caller on the first attempt.
     * <p>
     * Note: React CPU brownouts observed in production last 1-3 minutes, so a retry will not
     * recover a request stalled by one of those - it recovers sub-second connection blips. The
     * timeout above is what bounds user-visible latency. Set to 0 to disable retries.
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
     * Upper bound on the backoff between auth request attempts, which doubles from
     * {@link #requestRetryMinBackoff} and is capped here.
     */
    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 30, unit = TimeUnit.SECONDS)
    private Duration requestRetryMaxBackoff;

    /**
     * Cross-field constraint: a backoff whose minimum exceeds its maximum is contradictory, and
     * the doubling loop would silently clamp every attempt to the maximum rather than fail.
     * Validate it at startup so a bad config is a boot failure with a clear message, not a runtime
     * surprise.
     */
    @JsonIgnore
    @AssertTrue(message = "authentication.requestRetryMinBackoff must not exceed authentication.requestRetryMaxBackoff") public boolean isRetryBackoffRangeValid() {
        return requestRetryMinBackoff == null
                || requestRetryMaxBackoff == null
                || requestRetryMinBackoff.toMilliseconds() <= requestRetryMaxBackoff.toMilliseconds();
    }
}
