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
import lombok.ToString;
import org.apache.commons.lang3.StringUtils;

import java.util.concurrent.TimeUnit;

/**
 * ai-labels: AI-generated labels for every ended trace in opted-in workspaces, computed by opik-inference. Off by
 * default, and deliberately not a service toggle: {@code ServiceTogglesResource} returns those to the UI.
 */
@Data
public class AiLabelsConfig {

    @JsonProperty
    private boolean enabled = false;

    /** Optional directory of task YAML, loaded instead of the classpath tasks, so prompts can live outside the repo. */
    @JsonProperty
    private String tasksDir;

    @Valid @NotNull @JsonProperty
    private InferenceConfig inference = new InferenceConfig();

    /** Global in-flight engine calls: decide items × questions, plus generate items. */
    @JsonProperty
    @Min(1) private int budgetEngineCalls = 2_000;

    @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration tickInterval = Duration.seconds(30);

    @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration tickBudget = Duration.seconds(25);

    @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration sweepInterval = Duration.minutes(1);

    @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration healthInterval = Duration.minutes(5);

    @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration pollInterval = Duration.seconds(30);

    /** H: how far back, by trace created_at, traces are labeled. */
    @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.HOURS)
    @MaxDuration(value = 30, unit = TimeUnit.DAYS)
    private Duration horizon = Duration.days(7);

    /** Q: how long a trace must go without updates before it is labeled. */
    @NotNull @JsonProperty
    @MinDuration(value = 0, unit = TimeUnit.MINUTES)
    private Duration quietPeriod = Duration.minutes(30);

    @NotNull @JsonProperty
    @MinDuration(value = 0, unit = TimeUnit.MINUTES)
    private Duration sloSlack = Duration.minutes(15);

    @Valid @NotNull @JsonProperty
    private RetryConfig retry = new RetryConfig();

    /** P: a pending status older than this is treated as an orphan and resubmitted. */
    @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.MINUTES)
    private Duration pendingOrphanAge = Duration.hours(24);

    @JsonProperty
    @Min(1) @Max(10_000) private int chunkTraces = 1_000;

    /** C: the most candidates one sweep returns per workspace. */
    @JsonProperty
    @Min(1) @Max(100_000) private int perWorkspaceCandidates = 10_000;

    /** The service's own limit is 128 MiB. */
    @JsonProperty
    @Min(1) @Max(128L * 1024 * 1024) private long maxJobBytes = 32L * 1024 * 1024;

    /** The rendered state's share of the model input, in UTF-8 bytes (about 20k tokens at bytes ÷ 4). */
    @JsonProperty
    @Min(1) private long renderBudgetBytes = 80_000;

    @JsonProperty
    @Min(1) private long modelInputBudgetBytes = 100_000;

    @JsonProperty
    @Min(1) @Max(64) private int renderThreads = 4;

    @JsonProperty
    @Min(1) @Max(100_000) private int maxSpansPerTrace = 2_000;

    /** A trace or span field larger than this is replaced whole in SQL, before it reaches the JVM. */
    @JsonProperty
    @Min(1) private long maxFieldBytes = 1024L * 1024;

    @Valid @NotNull @JsonProperty
    private SweepConfig sweep = new SweepConfig();

    @JsonIgnore
    @AssertTrue(message = "aiLabels.inference.url (AI_LABELS_INFERENCE_URL) and aiLabels.inference.credential (AI_LABELS_INFERENCE_CREDENTIAL) must be set when aiLabels.enabled (AI_LABELS_ENABLED) is true")
    public boolean isInferenceConfigured() {
        return !enabled || (StringUtils.isNotBlank(inference.getUrl())
                && StringUtils.isNotBlank(inference.getCredential()));
    }

    @JsonIgnore
    @AssertTrue(message = "aiLabels.tickBudget must be shorter than aiLabels.tickInterval") public boolean isTickBudgetValid() {
        return tickBudget == null || tickInterval == null
                || tickBudget.toMilliseconds() < tickInterval.toMilliseconds();
    }

    @JsonIgnore
    @AssertTrue(message = "aiLabels.renderBudgetBytes must be smaller than aiLabels.modelInputBudgetBytes") public boolean isRenderBudgetValid() {
        return renderBudgetBytes < modelInputBudgetBytes;
    }

    @Data
    public static class InferenceConfig {

        @JsonProperty
        private String url;

        @ToString.Exclude
        @JsonProperty
        private String credential;

        @NotNull @JsonProperty
        @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
        private Duration connectTimeout = Duration.seconds(5);

        @NotNull @JsonProperty
        @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
        private Duration readTimeout = Duration.seconds(30);
    }

    @Data
    public static class RetryConfig {

        /** X: Opik-side attempts per (trace, task), on top of the service's own retries. */
        @JsonProperty
        @Min(1) @Max(10) private int maxAttempts = 2;

        /** D: the wait before a retryable failure is picked up again. */
        @NotNull @JsonProperty
        @MinDuration(value = 0, unit = TimeUnit.MINUTES)
        private Duration delay = Duration.minutes(15);
    }

    @Data
    public static class SweepConfig {

        /** N: enabled workspaces are split into this many buckets, one sweep query each. */
        @JsonProperty
        @Min(1) @Max(1_000) private int buckets = 8;

        @JsonProperty
        @Min(1) private long maxRowsToRead = 30_000_000L;

        @JsonProperty
        @Min(1) private long maxMemoryUsage = 4L * 1024 * 1024 * 1024;

        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        private Duration maxExecutionTime = Duration.seconds(50);
    }
}
