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

    // Field initializers (not @Builder.Default) so the defaults apply during Dropwizard's
    // YAML deserialization (no-args constructor), not only when the builder is used.

    /**
     * Master switch for the agentic-tools path on LLM-as-judge online scoring. When false,
     * the inline path is used regardless of context size — the model may overflow its window
     * on huge traces, but no tool-call loop is triggered.
     */
    @JsonProperty
    private boolean agenticToolsEnabled = true;

    /**
     * Estimated-tokens threshold above which the LLM-as-judge online scorer routes through
     * the agentic-tools path (read/jq/search tools). Sized for 128 K-token model windows;
     * bump higher on larger windows to keep more rules on the cheaper inline path.
     */
    @JsonProperty
    @Min(1) private int agenticToolsThresholdTokens = 50_000;

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
