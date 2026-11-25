package com.comet.opik.infrastructure;

import com.comet.opik.api.validation.SecureHttpUrl;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * The opik-inference client that ai-labels, guardrail checks and Topics call it with. Every value comes from
 * {@code config.yml}, so there are no defaults here.
 */
@Data
public class InferenceConfig {

    /**
     * The credential and the traces go with every call, so checked whenever it is set, whatever feature uses it: https,
     * or http only to localhost or a loopback address.
     */
    @SecureHttpUrl
    @JsonProperty
    private String url;

    @ToString.Exclude
    @JsonProperty
    private String credential;

    @NotNull @JsonProperty
    @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration connectTimeout;

    @NotNull @JsonProperty
    @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration readTimeout;

    /**
     * Synchronous decide calls in flight from this instance, each on a thread of its own. They share the
     * {@code jerseyClient} connection pool with the other calls to opik-inference.
     */
    @JsonProperty
    @Min(1) private int maxConcurrentDecides;

    /**
     * Synchronous generate calls in flight from this instance, each on a thread of its own for the model's whole
     * answer. They share the {@code jerseyClient} connection pool with the other calls to opik-inference.
     */
    @JsonProperty
    @Min(1) private int maxConcurrentGenerates;

    /**
     * The most of a response body, success or error, that a call reads; a longer one fails the call unread. Every call
     * in flight can hold up to this much heap, so it is capped at 64 MiB.
     */
    @JsonProperty
    @Min(1) @Max(64L * 1024 * 1024) private long maxResponseBytes;

    @Valid @NotNull @JsonProperty
    private LimitsConfig limits;

    @Valid @NotNull @JsonProperty
    private RetryConfig retry;
    @Valid @NotNull @JsonProperty
    private RealtimeConfig realtime;

    /** Whether a feature can call opik-inference: the URL and the credential are both set. */
    @JsonIgnore
    public boolean isConfigured() {
        return StringUtils.isNotBlank(url) && StringUtils.isNotBlank(credential);
    }

    /**
     * What the service accepts in one call. The defaults are the contract's limits; a deployment whose service is set
     * lower or higher sets these to match, since a call over the service's limit is rejected.
     */
    @Data
    public static class LimitsConfig {

        /** Inputs in one {@code /v1/embed} call. */
        @JsonProperty
        @Min(1) private int maxEmbedInputs;

        /** The most {@code deadline_ms} an embed call sends; the service's {@code embed_max_deadline_ms}. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
        private Duration maxEmbedDeadline;

        /** The most {@code deadline_ms} a realtime-lane generate call sends. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
        private Duration maxGenerateRealtimeDeadline;

        /** The most {@code deadline_ms} a batch-lane generate call sends. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
        private Duration maxGenerateBatchDeadline;

        /** Items in one batch submit. */
        @JsonProperty
        @Min(1) private int maxBatchItems;

        /** Job ids in one status call. */
        @JsonProperty
        @Min(1) private int maxStatusJobIds;
    }

    @Data
    public static class RetryConfig {

        /** Retries after the first attempt, for a retryable failure. */
        @JsonProperty
        @Min(0) @Max(10) private int maxRetries;

        /**
         * The wait before the first retry, doubled for each one after and jittered by up to half either way, unless
         * the service sends Retry-After.
         */
        @NotNull @JsonProperty
        @MinDuration(value = 0, unit = TimeUnit.MILLISECONDS)
        private Duration baseBackoff;

        /**
         * The service reads and enqueues a submit's whole body before it answers, so the read timeout grows by a second
         * per this many bytes sent.
         */
        @JsonProperty
        @Min(1) private long submitBytesPerSecond;
    }

    /** The synchronous calls a request waits on: what a guardrail check's decide call asks of the service. */
    @Data
    public static class RealtimeConfig {
        /** The deadline a decide call sends, the time the service may spend on it; at most the realtime lane's limit. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
        @MaxDuration(value = 5, unit = TimeUnit.SECONDS)
        private Duration decideDeadline;
        /** Room past that deadline for the answer to arrive, or for a quick retry of an overloaded call. */
        @NotNull @JsonProperty
        @MinDuration(value = 0, unit = TimeUnit.MILLISECONDS)
        private Duration decideMargin;
    }
}
