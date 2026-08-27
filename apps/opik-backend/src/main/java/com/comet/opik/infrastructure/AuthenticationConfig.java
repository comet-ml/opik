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
     * so normal calls never reach it, while a stalled call fails in 3s instead of 30s.
     * Set to 0 to disable the override and inherit the shared client timeout.
     */
    @Valid @NotNull @JsonProperty
    @MinDuration(value = 0, unit = TimeUnit.MILLISECONDS)
    // Must not exceed the shared jerseyClient timeout (30s) -- a larger value would be inert,
    // since the shared client would time out first.
    @MaxDuration(value = 30, unit = TimeUnit.SECONDS)
    private Duration requestTimeout = Duration.seconds(3);

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
    @Min(0)
    // Capped deliberately: each retry re-issues an auth call against a React service that may
    // already be CPU-starved, and retries cannot recover a multi-minute brownout anyway.
    @Max(5) private int requestMaxRetries = 1;

    /**
     * Minimum backoff between auth request attempts. Deliberately non-zero: an immediate retry
     * adds load to a React service that may already be CPU-starved.
     */
    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 10, unit = TimeUnit.SECONDS)
    private Duration requestRetryMinBackoff = Duration.milliseconds(250);

    /**
     * Upper bound on the exponential backoff between auth request attempts.
     */
    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 30, unit = TimeUnit.SECONDS)
    private Duration requestRetryMaxBackoff = Duration.seconds(1);

    /**
     * Cross-field constraint: an exponential backoff whose minimum exceeds its maximum is
     * contradictory, and Reactor would clamp it silently rather than fail. Validate it at startup
     * so a bad config is a boot failure with a clear message, not a runtime surprise.
     */
    @JsonIgnore
    @AssertTrue(message = "authentication.requestRetryMinBackoff must not exceed authentication.requestRetryMaxBackoff") public boolean isRetryBackoffRangeValid() {
        return requestRetryMinBackoff == null
                || requestRetryMaxBackoff == null
                || requestRetryMinBackoff.toMilliseconds() <= requestRetryMaxBackoff.toMilliseconds();
    }
}
