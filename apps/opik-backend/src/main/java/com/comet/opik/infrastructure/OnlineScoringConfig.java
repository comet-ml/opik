package com.comet.opik.infrastructure;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.concurrent.TimeUnit;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class OnlineScoringConfig {

    public static final String PAYLOAD_FIELD = "message";

    @JsonProperty
    @NotBlank private String consumerGroupName;

    @JsonProperty
    @Min(1) private int consumerBatchSize;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    private Duration poolingInterval;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 20, unit = TimeUnit.SECONDS)
    private Duration longPollingDuration;

    @JsonProperty
    @Min(2) private int claimIntervalRatio;

    @Valid @JsonProperty
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration pendingMessageDuration;

    @JsonProperty
    @Min(1) @Max(10) private int maxRetries;

    @JsonProperty
    @Min(1000) @Max(10_000_000) private int streamMaxLen;

    @JsonProperty
    @Min(0) @Max(10_000) private int streamTrimLimit;

    @Valid @JsonProperty
    @NotEmpty private List<@NotNull @Valid StreamConfiguration> streams;

    /**
     * Estimated-tokens threshold above which the LLM-as-judge online scorer routes through
     * the agentic-tools path (read/jq/search tools). Sized for 128 K-token model windows;
     * bump higher on larger windows to keep more rules on the cheaper inline path.
     *
     * <p>Floor at 1000 tokens — below that any non-trivial trace would flip onto the
     * tools path, so a typo'd env var (e.g. {@code =1}) would silently put the whole
     * online-scoring fleet on the agentic path. Dropwizard fails fast on startup instead.
     *
     * <p>Field initializer (not {@code @Builder.Default}) so the default applies during
     * Dropwizard's YAML deserialization (no-args constructor), not only via the builder.
     */
    @JsonProperty
    @Min(1000) private int agenticToolsThresholdTokens = 50_000;

    /**
     * Characters-per-token ratio used by {@code estimateTraceContextTokens} to translate
     * the {@code {trace, spans}} serialized JSON length into a token estimate. 4 is the
     * widely cited natural-language English approximation; random/code content runs closer
     * to 2, but the agentic-tools threshold itself has slack so this isn't precision-critical.
     * Configurable so operators can tune for workloads that skew toward code/JSON (lower
     * ratio = more pessimistic estimate = earlier switch to the agentic-tools path).
     */
    @JsonProperty
    @Min(1) private int agenticToolsCharsPerToken = 4;

    /**
     * Maximum total bytes of attachment data that may be injected as multimodal content
     * across a single judge invocation. Every injected attachment is re-sent on every
     * follow-up tool round and on the final structured re-issue, so the cost is
     * multiplicative in the number of rounds. 50 MB (default) accommodates several
     * high-resolution images without blowing context; lower this on memory-constrained
     * deployments.
     *
     * <p>Floor at 1 MB — below that almost no real attachment fits, so a typo'd env var
     * would silently disable attachment injection rather than failing fast. Dropwizard
     * catches the violation on startup instead.
     */
    @JsonProperty
    @Min(1_048_576) private long agenticToolsMaxInjectedBytes = 50L * 1024 * 1024;

    @Data
    @Builder(toBuilder = true)
    @NoArgsConstructor
    @AllArgsConstructor
    public static class StreamConfiguration {
        @JsonProperty
        @NotBlank private String scorer;

        @JsonProperty
        @NotBlank private String streamName;

        @JsonProperty
        @NotBlank private String codec;

        @JsonProperty
        @Min(1) private Integer consumerBatchSize;

        @Valid @JsonProperty
        @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
        private Duration poolingInterval;

        @Valid @JsonProperty
        @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
        @MaxDuration(value = 20, unit = TimeUnit.SECONDS)
        private Duration longPollingDuration;

        @JsonProperty
        @Min(2) private Integer claimIntervalRatio;

        @Valid @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MINUTES)
        private Duration pendingMessageDuration;

        @JsonProperty
        @Min(1) @Max(10) private Integer maxRetries;

        @JsonProperty
        @Min(1000) @Max(10_000_000) private Integer streamMaxLen;

        @JsonProperty
        @Min(0) @Max(10_000) private Integer streamTrimLimit;
    }
}
