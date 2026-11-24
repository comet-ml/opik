package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.redis.RedisStreamCodec;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;
import org.redisson.client.codec.Codec;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Topics settings; config.yml describes each key and its default. Deliberately not a service toggle. Needs ai-labels
 * on and uses its opik-inference deployment and credential: see {@code OpikConfiguration#isTopicsAiLabelsEnabled}.
 */
@Data
public class TopicsConfig {

    @Valid @NotNull @JsonProperty
    private AlgorithmConfig algorithm;

    @Valid @NotNull @JsonProperty
    private BuildConfig build;

    @Valid @NotNull @JsonProperty
    private FirstBuildConfig firstBuild;

    @Valid @NotNull @JsonProperty
    private RunsConfig runs;

    @Valid @NotNull @JsonProperty
    private StorageConfig storage;

    /**
     * The k-means topic algorithm's tunables: quality against run time and prompt length. They depend on the embedding
     * model and the deployment, so they live here; values fixed by the algorithm stay in its code.
     */
    @Data
    public static class AlgorithmConfig {

        @JsonProperty
        @Min(2) @Max(1_000) private int minGroups;

        @JsonProperty
        @Min(2) @Max(1_000) private int maxGroups;

        @JsonProperty
        @Min(1) @Max(10_000) private int textsPerGroup;

        @JsonProperty
        @Min(1) @Max(1_000) private int kmeansMaxIterations;

        @JsonProperty
        @Min(1) @Max(100) private int kmeansRestarts;

        @JsonProperty
        @Min(0) @Max(100) private int authorFrequent;

        @JsonProperty
        @Min(1) @Max(100_000) private int authorBudget;

        @JsonProperty
        @Min(1) @Max(100) private int authorMinExamples;

        @JsonProperty
        @Min(1) @Max(100) private int authorMaxExamples;

        @JsonProperty
        @DecimalMin("0.0") @DecimalMax("1.0") private double assignMinCosine;

        @JsonProperty
        @DecimalMin("0.0") @DecimalMax("1.0") private double otherGate;

        /** Most topics in a project's list. */
        @JsonProperty
        @Min(1) @Max(100) private int maxTopics;

        /** A first build over fewer traces is marked provisional in its stats. */
        @JsonProperty
        @Min(0) @Max(100_000) private int provisionalBelow;

        @JsonProperty
        @Min(1) @Max(100_000) private int nightlyMinCandidate;

        @JsonProperty
        @Min(2) @Max(1_000) private int nightlyMinGroups;

        @JsonProperty
        @Min(2) @Max(1_000) private int nightlyMaxGroups;

        @JsonProperty
        @Min(1) @Max(10_000) private int nightlyTextsPerGroup;

        @JsonProperty
        @Min(0) @Max(100) private int nightlyFrequent;

        @JsonProperty
        @Min(1) @Max(100) private int nightlyExamples;

        @JsonProperty
        @Min(1) @Max(365) private int recentDays;

        @JsonProperty
        @DecimalMin("0.0") @DecimalMax("1.0") private double retireShare;

        @JsonProperty
        @Min(1) @Max(1_000) private int retireNights;

        @JsonIgnore
        @AssertTrue(message = "topics.algorithm.nightlyMinGroups must not be above nightlyMaxGroups") public boolean isNightlyGroupsOrdered() {
            return nightlyMinGroups <= nightlyMaxGroups;
        }

        @JsonIgnore
        @AssertTrue(message = "topics.algorithm.nightlyFrequent must not be above nightlyExamples") public boolean isNightlyExamplesOrdered() {
            return nightlyFrequent <= nightlyExamples;
        }

        @JsonIgnore
        @AssertTrue(message = "topics.algorithm.minGroups must not be above maxGroups") public boolean isGroupsOrdered() {
            return minGroups <= maxGroups;
        }

        @JsonIgnore
        @AssertTrue(message = "topics.algorithm.authorMinExamples must not be above authorMaxExamples") public boolean isAuthorExamplesOrdered() {
            return authorMinExamples <= authorMaxExamples;
        }
    }

    @JsonProperty
    private boolean enabled;

    /**
     * A String because Dropwizard substitutes env vars as scalars, so a comma-separated value cannot bind to a
     * collection. Null and blank both mean none. No getter: read it via {@link #enabledWorkspaceIds()}.
     */
    @JsonProperty
    @Getter(AccessLevel.NONE)
    private String enabledWorkspaces;

    @Valid @NotNull @JsonProperty
    private VectorsConfig vectors;

    /** A topic list build's settings: a first build, a nightly update and their labelling pass. */
    @Data
    public static class BuildConfig {

        @Valid @NotNull @JsonProperty
        private BuildReadConfig read;

        /** One project's run is stopped and failed with {@code timeout} after this. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MINUTES)
        @MaxDuration(value = 2, unit = TimeUnit.HOURS)
        private Duration projectTimeout;

        /** A project is built when its window holds at least this many traces with a vector. */
        @JsonProperty
        @Min(2) @Max(100_000) private int minVectors;

        /** The window: this many whole UTC days before the day the window ends on. */
        @JsonProperty
        @Min(1) @Max(90) private int windowDays;

        /**
         * A larger window is sampled: the run finds topics on this many of its traces, spread evenly by a hash of the
         * trace id, and then labels every trace of the window.
         */
        @JsonProperty
        @Min(100) @Max(100_000) private int maxVectors;

        /** The deadline of the LLM call that names the groups. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 300, unit = TimeUnit.SECONDS)
        private Duration llmDeadline;

        /**
         * A run's ClickHouse window read stops after this (or the project timeout, when shorter); so does each attempt
         * of its {@code trace_topics} insert.
         */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 30, unit = TimeUnit.MINUTES)
        private Duration readTimeout;

        /** A ClickHouse count of a window: the labelling pass's, which also takes the slice bounds, and the planner's. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 30, unit = TimeUnit.MINUTES)
        private Duration countTimeout;

        /** The labelling pass's page: traces read, labelled and inserted at once. */
        @JsonProperty
        @Min(1) @Max(100_000) private int labelPageTraces;

        /** The labelling pass's slice of the window: a page's read costs about one slice of keys. */
        @JsonProperty
        @Min(1) @Max(1_000_000) private int labelSliceTraces;

        /** Retries of the {@code trace_topics} insert after the save's commit. */
        @JsonProperty
        @Min(0) @Max(10) private int insertRetries;

        /** The first backoff before an insert retry; it doubles each time. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
        @MaxDuration(value = 1, unit = TimeUnit.MINUTES)
        private Duration insertBackoff;

        @JsonIgnore
        @AssertTrue(message = "topics.build.llmDeadline must be shorter than topics.build.projectTimeout") public boolean isLlmDeadlineValid() {
            return llmDeadline == null || projectTimeout == null
                    || llmDeadline.toMilliseconds() < projectTimeout.toMilliseconds();
        }

        /** A sample of {@code maxVectors} traces must still hold the {@code minVectors} a build needs. */
        @JsonIgnore
        @AssertTrue(message = "topics.build.maxVectors must be at least topics.build.minVectors") public boolean isMaxVectorsValid() {
            return maxVectors >= minVectors;
        }
    }

    /**
     * The ClickHouse guards of the build's reads: the window count, the window read, the unlabelled read and the topic
     * counts. Their own, not the vectors tick's: the build reads a whole window, the window read about three times.
     */
    @Data
    public static class BuildReadConfig {

        @JsonProperty
        @Min(1) @Max(1_000_000_000L) private long maxRowsToRead;

        @JsonProperty
        @Min(1) @Max(8L * 1024 * 1024 * 1024) private long maxMemoryUsage;

        @JsonProperty
        @Min(1) @Max(16) private int maxThreads;

        /** ClickHouse query priority; a higher number yields to lower ones. 0 is none. */
        @JsonProperty
        @Min(0) @Max(1000) private int priority;

        /** Rows per block of the window count, whose in-flight per-trace arrays grow with it. */
        @JsonProperty
        @Min(1024) @Max(1_048_576) private int maxBlockSize;

        /** The topic counts read the window in slices of this much creation time, one query each. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.HOURS)
        @MaxDuration(value = 7, unit = TimeUnit.DAYS)
        private Duration countSlice;
    }

    /** The topics state's MySQL writes. */
    @Data
    public static class StorageConfig {

        /**
         * Projects per transaction when the topics rows of deleted projects are deleted, so one delete stays bounded
         * however many projects went at once.
         */
        @JsonProperty
        @Min(1) @Max(10_000) private int deleteBatchSize;
    }

    /**
     * The parsed, stripped, blank-free set of listed workspace ids. Deliberately neither cached nor named as a bean
     * getter: Lombok's toString/equals/hashCode and Jackson call getters, and a parse cached before the field was bound
     * would keep an empty list for the life of the process.
     */
    public Set<String> enabledWorkspaceIds() {
        return Arrays.stream(Objects.toString(enabledWorkspaces, "").split(","))
                .map(String::strip)
                .filter(workspaceId -> !workspaceId.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** Which projects without a list the planner queues a first build for. */
    @Data
    public static class FirstBuildConfig {

        /**
         * A project's first build waits for its vectors backfill to drain. Past this long since the backfill started,
         * it is built anyway once {@code timedOutShare} of the backfill's traces have a vector; with fewer it keeps
         * waiting.
         */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MINUTES)
        @MaxDuration(value = 30, unit = TimeUnit.DAYS)
        private Duration backfillMaxWait;

        @JsonProperty
        @DecimalMin("0.5") @DecimalMax("1.0") private double timedOutShare;

        /** The wait after a project's first, second, ... failed first build; the last applies to every later one. */
        @NotNull @JsonProperty
        @Size(min = 1, max = 10) private List<@NotNull Duration> retryDelays;
    }

    /**
     * Topics runs across pods. The leader's planner queues runs on {@code topic_projects}, capped at
     * {@code maxInFlight} across the cluster, and publishes each on this stream; any pod claims one with a
     * compare-and-set on the row and runs it, {@code consumerBatchSize} at a time.
     */
    @Data
    public static class RunsConfig implements StreamConfiguration {

        public static final String PAYLOAD_FIELD = "message";

        @NotNull @JsonProperty
        private String streamName;

        @NotNull @JsonProperty
        private String consumerGroupName;

        /** Runs one pod runs at a time: each holds a window in memory. */
        @JsonProperty
        @Min(1) @Max(8) private int consumerBatchSize;

        @NotNull @JsonProperty
        @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
        private Duration poolingInterval;

        @NotNull @JsonProperty
        @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
        @MaxDuration(value = 20, unit = TimeUnit.SECONDS)
        private Duration longPollingDuration;

        @JsonProperty
        @Min(2) private int claimIntervalRatio;

        /**
         * A delivered message no pod acked for this long goes to another pod: a stopped pod's runs, put back to
         * {@code queued}, run there. A run still going keeps its row {@code running}, so that pod's claim fails and
         * it only acks the message.
         */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MINUTES)
        private Duration pendingMessageDuration;

        @JsonProperty
        @Min(1) @Max(10) private int maxRetries;

        @JsonProperty
        @Min(1000) @Max(10_000_000) private int streamMaxLen;

        @JsonProperty
        @Min(0) @Max(10_000) private int streamTrimLimit;

        /** Runs queued, running or cancelling across the cluster: each reads a window from ClickHouse and calls the LLM. */
        @JsonProperty
        @Min(1) @Max(100) private int maxInFlight;

        /** How often a running run writes its heartbeat; a write that updates no row stops it. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 5, unit = TimeUnit.MINUTES)
        private Duration heartbeatInterval;

        /** A run whose heartbeat is older than this lost its pod, and is recorded {@code abandoned}. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 1, unit = TimeUnit.HOURS)
        private Duration abandonGrace;

        /** A run queued this long ago and not claimed is published again: its message was lost. */
        @NotNull @JsonProperty
        @MinDuration(value = 10, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 1, unit = TimeUnit.HOURS)
        private Duration republishAfter;

        /** The planner stops counting candidates after this, and queues what it found. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 30, unit = TimeUnit.MINUTES)
        private Duration planBudget;

        @Override
        @JsonIgnore
        public Codec getCodec() {
            return RedisStreamCodec.JAVA.getCodec();
        }

        @JsonIgnore
        @AssertTrue(message = "topics.runs.abandonGrace must be above topics.runs.heartbeatInterval") public boolean isAbandonGraceValid() {
            return abandonGrace == null || heartbeatInterval == null
                    || abandonGrace.toMilliseconds() > heartbeatInterval.toMilliseconds();
        }
    }

    @Data
    public static class VectorsConfig {

        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 1, unit = TimeUnit.HOURS)
        private Duration tickInterval;

        /** Every ClickHouse query and embed call of a tick ends within this; what is left waits for the next tick. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 30, unit = TimeUnit.MINUTES)
        private Duration tickBudget;

        /** How far back, by status write time, each tick looks for labeled traces without a vector. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MINUTES)
        @MaxDuration(value = 30, unit = TimeUnit.DAYS)
        private Duration window;

        /**
         * How far before its watermark a workspace's read starts: covers a done status that becomes visible after a
         * later status was read, such as one written to a lagging replica or before its output row. A status later than
         * this is left to the daily pass. At most {@code window}.
         */
        @NotNull @JsonProperty
        @MinDuration(value = 0, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 1, unit = TimeUnit.DAYS)
        private Duration watermarkOverlap;

        /** The window of the daily pass, for rows the fresh phase missed, and of a project's backfill. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MINUTES)
        @MaxDuration(value = 90, unit = TimeUnit.DAYS)
        private Duration longWindow;

        /** How often the daily long-window pass starts, and the tick discovers each workspace's projects. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 7, unit = TimeUnit.DAYS)
        private Duration longWindowInterval;

        /** Above zero: replaces {@code longWindow} for the backfill phase and the daily pass. 0 is off. */
        @NotNull @JsonProperty
        @MinDuration(value = 0, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 365, unit = TimeUnit.DAYS)
        private Duration backfillWindow;

        /** The most traces one tick embeds per workspace, and per project in the backfill phase. */
        @JsonProperty
        @Min(1) @Max(100_000) private int maxTracesPerTick;

        /** Texts per embed call, up to {@code inference.limits.maxEmbedInputs}. */
        @JsonProperty
        @Min(1) private int embedBatchSize;

        /** Embed calls in flight at a time; set it to the embed pool's replica count. */
        @JsonProperty
        @Min(1) private int embedConcurrency;

        /**
         * Each embed call's deadline, up to {@code inference.limits.maxEmbedDeadline}; a chunk starts only with at least
         * this much of the tick budget left.
         */
        @NotNull @JsonProperty
        @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
        private Duration embedTimeout;

        @Valid @NotNull @JsonProperty
        private FindConfig find;

        @JsonIgnore
        @AssertTrue(message = "topics.vectors.tickBudget must be shorter than topics.vectors.tickInterval") public boolean isTickBudgetValid() {
            return tickBudget == null || tickInterval == null
                    || tickBudget.toMilliseconds() < tickInterval.toMilliseconds();
        }

        @JsonIgnore
        @AssertTrue(message = "topics.vectors.watermarkOverlap must not be longer than topics.vectors.window") public boolean isWatermarkOverlapValid() {
            return watermarkOverlap == null || window == null
                    || watermarkOverlap.toMilliseconds() <= window.toMilliseconds();
        }

        @JsonIgnore
        @AssertTrue(message = "topics.vectors.longWindow must not be shorter than topics.vectors.window") public boolean isLongWindowValid() {
            return window == null || longWindow == null || longWindow.toMilliseconds() >= window.toMilliseconds();
        }

        // A read starts only with a whole second plus one embed call left in the tick (TickBudget.hasTimeForRead), so a
        // shorter budget would read nothing.
        @JsonIgnore
        @AssertTrue(message = "topics.vectors.tickBudget must be at least topics.vectors.embedTimeout plus 1s") public boolean isEmbedTimeoutValid() {
            return embedTimeout == null || tickBudget == null
                    || embedTimeout.toMilliseconds() + 1_000 <= tickBudget.toMilliseconds();
        }

        @JsonIgnore
        @AssertTrue(message = "topics.vectors.backfillWindow must be 0 or not shorter than topics.vectors.window") public boolean isBackfillWindowValid() {
            return backfillWindow == null || window == null || backfillWindow.toMilliseconds() == 0
                    || backfillWindow.toMilliseconds() >= window.toMilliseconds();
        }

        @JsonIgnore
        @AssertTrue(message = "topics.vectors.find.maxExecutionTime must not be longer than topics.vectors.tickBudget") public boolean isFindMaxExecutionTimeValid() {
            return find == null || find.getMaxExecutionTime() == null || tickBudget == null
                    || find.getMaxExecutionTime().toMilliseconds() <= tickBudget.toMilliseconds();
        }
    }

    /** Guards of the window read, as {@code aiLabels.sweep}'s; a trip fails that workspace's read for the tick. */
    @Data
    public static class FindConfig {

        @JsonProperty
        @Min(1) @Max(1_000_000_000L) private long maxRowsToRead;

        @JsonProperty
        @Min(1) @Max(8L * 1024 * 1024 * 1024) private long maxMemoryUsage;

        /** ClickHouse threads per read, so the tick stays a small share of the server. */
        @JsonProperty
        @Min(1) @Max(16) private int maxThreads;

        /** Cut to the time left in the tick. At most {@code tickBudget}. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        private Duration maxExecutionTime;

        /** The margin below {@code lowerBound - aiLabels.horizon} for the time from the sweep to the status write. */
        @NotNull @JsonProperty
        @MinDuration(value = 0, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 7, unit = TimeUnit.DAYS)
        private Duration createdAtSlack;

        /** The daily pass and a large project's backfill read their window in slices of this much creation time. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.HOURS)
        @MaxDuration(value = 365, unit = TimeUnit.DAYS)
        private Duration sliceSize;

        /**
         * The backfill's discovery and vector count read in slices of this much creation time. Their memory stays
         * small, so wide slices: each slice re-reads the granules across its edges.
         */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.HOURS)
        @MaxDuration(value = 365, unit = TimeUnit.DAYS)
        private Duration discoverySliceSize;
    }
}
