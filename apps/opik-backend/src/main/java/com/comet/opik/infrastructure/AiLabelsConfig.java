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

    /**
     * Global in-flight work: the cost of every submitted, not yet loaded, item (see {@link #perCallBytes} and
     * {@link #minItemBytes}). It is the queue a new trace waits behind in opik-inference, so it is set as throughput
     * times the wait accepted: 16 MiB is about 3 minutes of one L40S on the 4B model (22K tokens/s, about 88 KB of cost
     * a second). Below what the dispatchers hold plus a tick of throughput, the GPU starves between ticks.
     */
    @JsonProperty
    @Min(1) private long budgetBytes = 16L * 1024 * 1024;

    /**
     * What an engine call costs beyond its input, in bytes: scheduling and the few output tokens. A decide item makes
     * one call per question, a generate item one. Fit from the pool's load test.
     */
    @JsonProperty
    @Min(0) private long perCallBytes = 2_000;

    /**
     * The least an item costs. Turns render about {@code share / minItemBytes} traces, so it sits near a typical item;
     * small items are overcharged. Keep it under {@code budgetBytes / (2 × the dispatchers' batch slots)}, so the
     * budget holds enough tiny items to keep them busy.
     */
    @JsonProperty
    @Min(1) private long minItemBytes = 16L * 1024;

    @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration tickInterval = Duration.seconds(60);

    @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration tickBudget = Duration.seconds(25);

    /** Under the tick interval, so every tick sweeps: a tick only submits what its own sweep found. */
    @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration sweepInterval = Duration.seconds(55);

    @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration healthInterval = Duration.minutes(5);

    /**
     * H: how far back, by trace created_at, traces are labeled. The sweep takes the newest first, so under overload the
     * oldest age out of H unlabeled; it also bounds the opt-in backfill and the inference outage recovered from. The
     * sweep scans every trace in H each tick, so its cost grows with H.
     */
    @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.HOURS)
    @MaxDuration(value = 30, unit = TimeUnit.DAYS)
    private Duration horizon = Duration.days(7);

    /** Q: how long a trace must go without updates before it is labeled. */
    @NotNull @JsonProperty
    @MinDuration(value = 0, unit = TimeUnit.MINUTES)
    private Duration quietPeriod = Duration.hours(1);

    @NotNull @JsonProperty
    @MinDuration(value = 0, unit = TimeUnit.MINUTES)
    private Duration sloSlack = Duration.minutes(15);

    @Valid @NotNull @JsonProperty
    private RetryConfig retry = new RetryConfig();

    /** The most traces one submit turn renders. */
    @JsonProperty
    @Min(1) @Max(10_000) private int chunkTraces = 1_000;

    /** Finished jobs whose results are read in parallel. */
    @JsonProperty
    @Min(1) @Max(64) private int loadConcurrency = 4;

    /** A tick stops starting job loads once it holds this many label and status rows, so one insert stays bounded. */
    @JsonProperty
    @Min(1) private int loadMaxRows = 200_000;

    /**
     * C: the most candidates one sweep returns per workspace. One tick admits at most {@code budgetBytes / minItemBytes}
     * traces (1,024 at the defaults), so more are swept, held and dropped every tick. Set it to about twice that, which
     * leaves room for traces that fail to render; scale it with the budget.
     */
    @JsonProperty
    @Min(1) @Max(100_000) private int perWorkspaceCandidates = 2_000;

    /**
     * A submit is one script on the service's shared Valkey, and its gateway rejects bodies over 8 MiB. Polling costs
     * one call per tick however many jobs there are, so bigger jobs gain nothing.
     */
    @JsonProperty
    @Min(1) @Max(128L * 1024 * 1024) private long maxJobBytes = 2L * 1024 * 1024;

    /** The rendered state's share of the model input, in UTF-8 bytes (about 12k tokens at bytes ÷ 4). */
    @JsonProperty
    @Min(1) private long renderBudgetBytes = 48L * 1024;

    @JsonProperty
    @Min(1) private long modelInputBudgetBytes = 100_000;

    @JsonProperty
    @Min(1) @Max(64) private int renderThreads = 4;

    /**
     * A trace's spans are read in start order up to this many; the rest are left out. 200 keeps every span of 99.8% of
     * the dev agent's traces (p99 117, max 848).
     */
    @JsonProperty
    @Min(1) @Max(100_000) private int maxSpansPerTrace = 200;

    /**
     * A trace's spans are read in start order until their input and output reach this; the rest are left out, as past
     * {@code maxSpansPerTrace}. Fitting keeps far less, so a trace's read is bounded without changing what a normal
     * trace renders to.
     */
    @JsonProperty
    @Min(1) private long maxSpanBytesPerTrace = 16L * 1024 * 1024;

    /** A chunk's spans are read in batches of traces whose span input and output add up to at most this. */
    @JsonProperty
    @Min(1) private long renderReadBytes = 64L * 1024 * 1024;

    /**
     * Each render query's {@code max_execution_time} on the shared ClickHouse. Under the tick budget, so a slow read
     * can't hold a tick for long; a chunk whose read times out is retried trace by trace until the tick's deadline.
     */
    @NotNull @JsonProperty
    @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    private Duration renderMaxExecutionTime = Duration.seconds(20);

    /** A trace or span field larger than this is replaced whole in SQL, before it reaches the JVM. */
    @JsonProperty
    @Min(1) private long maxFieldBytes = 1024L * 1024;

    @Valid @NotNull @JsonProperty
    private HistoryConfig history = new HistoryConfig();

    @Valid @NotNull @JsonProperty
    private SweepConfig sweep = new SweepConfig();

    @JsonIgnore
    @AssertTrue(message = "aiLabels.inference.url (AI_LABELS_INFERENCE_URL) and aiLabels.inference.credential (AI_LABELS_INFERENCE_CREDENTIAL) must be set when aiLabels.enabled (AI_LABELS_ENABLED) is true")
    public boolean isInferenceConfigured() {
        return !enabled || (inference != null && StringUtils.isNotBlank(inference.getUrl())
                && StringUtils.isNotBlank(inference.getCredential()));
    }

    @JsonIgnore
    @AssertTrue(message = "aiLabels.tickBudget must be shorter than aiLabels.tickInterval") public boolean isTickBudgetValid() {
        return tickBudget == null || tickInterval == null
                || tickBudget.toMilliseconds() < tickInterval.toMilliseconds();
    }

    @JsonIgnore
    @AssertTrue(message = "aiLabels.minItemBytes must be smaller than aiLabels.budgetBytes") public boolean isMinItemBytesValid() {
        return minItemBytes < budgetBytes;
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
        @MaxDuration(value = 1, unit = TimeUnit.DAYS)
        private Duration delay = Duration.minutes(15);
    }

    /** The earlier turns of a trace's thread, given to tasks whose prompt has {@code {{history}}}. */
    @Data
    public static class HistoryConfig {

        /** K: the most earlier turns read per trace. Fitting decides how many are kept. */
        @JsonProperty
        @Min(1) @Max(100) private int turns = 20;

        /** Each earlier turn's input and output is cut in the middle to this many UTF-8 bytes when read. */
        @JsonProperty
        @Min(64) @Max(1024 * 1024) private int maxFieldBytes = 2 * 1024;

        /** Earlier turns whose id time is more than this before the trace's are not read. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MINUTES)
        @MaxDuration(value = 30, unit = TimeUnit.DAYS)
        private Duration lookback = Duration.hours(24);
    }

    @Data
    public static class SweepConfig {

        /** N: enabled workspaces are split into this many buckets, one sweep query each. */
        @JsonProperty
        @Min(1) @Max(1_000) private int buckets = 8;

        @JsonProperty
        @Min(1) private long maxRowsToRead = 30_000_000L;

        @JsonProperty
        @Min(1) private long maxMemoryUsage = 1024L * 1024 * 1024;

        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        private Duration maxExecutionTime = Duration.seconds(50);
    }
}
