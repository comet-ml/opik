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
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.concurrent.TimeUnit;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
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

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    @MaxDuration(value = 1, unit = TimeUnit.DAYS)
    @Builder.Default
    private Duration organizationCacheTTL = Duration.minutes(10);

    @Valid @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    @MaxDuration(value = 7, unit = TimeUnit.DAYS)
    @Builder.Default
    private Duration organizationCacheStaleTTL = Duration.hours(24);

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

    /**
     * Redis drops a cached organization after the stale TTL, so a stale TTL shorter than the fresh one would drop
     * entries while they are still fresh: every request would ask Comet, and nothing would be left for Comet outages.
     */
    @JsonIgnore
    @AssertTrue(message = "authentication.organizationCacheStaleTTL must be at least authentication.organizationCacheTTL") public boolean isOrganizationCacheTtlOrderValid() {
        return organizationCacheTTL == null
                || organizationCacheStaleTTL == null
                || organizationCacheTTL.toMilliseconds() <= organizationCacheStaleTTL.toMilliseconds();
    }
}
