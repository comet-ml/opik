package com.comet.opik.infrastructure;

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
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Topics settings; config.yml describes each key and its default. Deliberately not a service toggle. Needs ai-labels
 * on and uses its opik-inference deployment and credential: see {@code OpikConfiguration#isTopicsAiLabelsEnabled}.
 */
@Slf4j
@Data
public class TopicsConfig {

    @Valid @NotNull @JsonProperty
    private AlgorithmConfig algorithm;

    @Valid @NotNull @JsonProperty
    private RunConfig run;

    @Valid @NotNull @JsonProperty
    private ReadConfig read;

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

        /** A discovery run over fewer traces is marked provisional in its stats. */
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

        @JsonProperty
        @DecimalMin("0.0") @DecimalMax("1.0") private double nightlyMinTightness;

        @JsonProperty
        @DecimalMin("0.0") @DecimalMax("1.0") private double nightlyBigShare;

        @JsonProperty
        @DecimalMin("0.0") @DecimalMax("1.0") private double nightlySameMemory;

        @JsonProperty
        @Min(1) @Max(10_000) private int nightlyVerifyTokensPerPair;

        @JsonProperty
        @Min(1) @Max(100) private int nightlyTopicExamples;

        @JsonProperty
        @Min(1) @Max(100) private int verifyTopicExamples;

        @JsonProperty
        @Min(1) @Max(100) private int verifyGroupExamples;

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
     * collection. Null and blank both mean none. Entries are {@code id} or {@code id:stage}; see {@link TopicsStage}.
     * No getter: read it via {@link #enabledWorkspaceStages()} or {@link #enabledWorkspaceIds()}.
     */
    @JsonProperty
    @Getter(AccessLevel.NONE)
    private String enabledWorkspaces;

    @Valid @NotNull @JsonProperty
    private VectorsConfig vectors;

    /** The last parse of {@link #enabledWorkspaces}, with the string it was parsed from. */
    @Builder(toBuilder = true)
    private record ParsedWorkspaces(String raw, Map<String, TopicsStage> stages) {
    }

    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private volatile ParsedWorkspaces parsedWorkspaces;

    /** A topic run's settings: a discovery run, a nightly update and their labelling pass. */
    @Data
    public static class RunConfig {

        @Valid @NotNull @JsonProperty
        private RunReadConfig read;

        /** One project's run is stopped and failed with {@code timeout} after this. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MINUTES)
        @MaxDuration(value = 2, unit = TimeUnit.HOURS)
        private Duration projectTimeout;

        /** A project gets a discovery run when its window holds at least this many traces with a vector. */
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

        /** The deadline of a nightly update's naming calls ({@code naming}, {@code naming_new}). */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 300, unit = TimeUnit.SECONDS)
        private Duration namingDeadline;

        /** The deadline of a nightly update's verify call ({@code verify_naming}). */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 300, unit = TimeUnit.SECONDS)
        private Duration verifyDeadline;

        /**
         * A run's ClickHouse window read stops after this (or the project timeout, when shorter); so does each attempt
         * of its {@code trace_topics} insert.
         */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 30, unit = TimeUnit.MINUTES)
        private Duration readTimeout;

        /** The labelling pass's count of its window, which also takes the slice bounds. */
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
        @AssertTrue(message = "topics.run.llmDeadline must be shorter than topics.run.projectTimeout") public boolean isLlmDeadlineValid() {
            return llmDeadline == null || projectTimeout == null
                    || llmDeadline.toMilliseconds() < projectTimeout.toMilliseconds();
        }

        @JsonIgnore
        @AssertTrue(message = "topics.run.namingDeadline must be shorter than topics.run.projectTimeout") public boolean isNamingDeadlineValid() {
            return namingDeadline == null || projectTimeout == null
                    || namingDeadline.toMilliseconds() < projectTimeout.toMilliseconds();
        }

        @JsonIgnore
        @AssertTrue(message = "topics.run.verifyDeadline must be shorter than topics.run.projectTimeout") public boolean isVerifyDeadlineValid() {
            return verifyDeadline == null || projectTimeout == null
                    || verifyDeadline.toMilliseconds() < projectTimeout.toMilliseconds();
        }

        /** A sample of {@code maxVectors} traces must still hold the {@code minVectors} a run needs. */
        @JsonIgnore
        @AssertTrue(message = "topics.run.maxVectors must be at least topics.run.minVectors") public boolean isMaxVectorsValid() {
            return maxVectors >= minVectors;
        }
    }

    /**
     * The ClickHouse guards of the run's reads: the window count, the window read, the unlabelled read and the topic
     * counts. Their own, not the vectors tick's: the run reads a whole window, the window read about three times.
     */
    @Data
    public static class RunReadConfig implements ReadGuards {

        @JsonProperty
        @Min(1) @Max(1_000_000_000L) private long maxRowsToRead;

        @JsonProperty
        @Min(1) @Max(8L * 1024 * 1024 * 1024) private long maxMemoryUsage;

        @JsonProperty
        @Min(1) @Max(16) private int maxThreads;

        /** ClickHouse query priority; a higher number yields to lower ones. 0 is none. */
        @JsonProperty
        @Min(0) @Max(1000) private int priority;

        /** Rows per block of the run's reads: the count's per-trace arrays and the reads' vectors grow with it. */
        @JsonProperty
        @Min(1024) @Max(1_048_576) private int maxBlockSize;

        /** The topic counts read the window in slices of this much creation time, one query each. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.HOURS)
        @MaxDuration(value = 7, unit = TimeUnit.DAYS)
        private Duration countSlice;
    }

    /** The ClickHouse guards a topics read binds: the run's and the read API's are two sets of them. */
    public interface ReadGuards {

        long getMaxRowsToRead();

        long getMaxMemoryUsage();

        int getMaxThreads();

        int getPriority();

        int getMaxBlockSize();
    }

    /**
     * The read API's ClickHouse guards, apart from the run's: a page request, not a background job. Its reads (the
     * per topic and day counts, the collecting count) share one time budget.
     */
    @Data
    public static class ReadConfig implements ReadGuards {

        @JsonProperty
        @Min(1) @Max(1_000_000_000L) private long maxRowsToRead;

        @JsonProperty
        @Min(1) @Max(8L * 1024 * 1024 * 1024) private long maxMemoryUsage;

        @JsonProperty
        @Min(1) @Max(16) private int maxThreads;

        /** ClickHouse priority of the collecting count; a higher number yields to lower ones. 0 is none. */
        @JsonProperty
        @Min(0) @Max(1000) private int priority;

        /** Rows per block of the collecting count, whose in-flight per-trace arrays grow with it. */
        @JsonProperty
        @Min(1024) @Max(1_048_576) private int maxBlockSize;

        /** A request's ClickHouse reads end within this; a read that does not is a guard trip. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 60, unit = TimeUnit.SECONDS)
        private Duration budget;
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
     * Each listed workspace id and its stage, from stripped, blank-free entries; an id without a suffix is at
     * {@link TopicsStage#ALL}. An id repeated with the same stage is listed once. Throws on an unknown stage or an id
     * listed with two stages, which {@link #isEnabledWorkspacesValid()} rejects at startup. Not named as a bean getter,
     * since Lombok and Jackson call getters. The parse is kept against the string it came from, so a value bound or set
     * later is parsed again rather than served stale.
     */
    public Map<String, TopicsStage> enabledWorkspaceStages() {
        var raw = enabledWorkspaces;
        var parsed = parsedWorkspaces;
        if (parsed == null || !Objects.equals(parsed.raw(), raw)) {
            parsed = ParsedWorkspaces.builder().raw(raw).stages(parse(raw)).build();
            parsedWorkspaces = parsed;
        }
        return parsed.stages();
    }

    private static Map<String, TopicsStage> parse(String enabledWorkspaces) {
        var stages = new HashMap<String, TopicsStage>();
        for (var entry : Objects.toString(enabledWorkspaces, "").split(",")) {
            var parts = entry.split(":", -1);
            var workspaceId = parts[0].strip();
            if (parts.length == 1 && workspaceId.isEmpty()) {
                continue;
            }
            if (workspaceId.isEmpty() || parts.length > 2) {
                throw new IllegalArgumentException("topics.enabledWorkspaces: malformed entry '%s'".formatted(entry));
            }
            var stage = parts.length == 1
                    ? TopicsStage.ALL
                    : TopicsStage.fromConfigName(parts[1].strip()).orElseThrow(() -> new IllegalArgumentException(
                            "topics.enabledWorkspaces: unknown stage in '%s'".formatted(entry)));
            var listed = stages.putIfAbsent(workspaceId, stage);
            if (listed != null && listed != stage) {
                throw new IllegalArgumentException(
                        "topics.enabledWorkspaces: workspace '%s' is listed with stages '%s' and '%s'"
                                .formatted(workspaceId, listed.configName(), stage.configName()));
            }
        }
        return Map.copyOf(stages);
    }

    /** The listed workspace ids, whatever their stage. */
    public Set<String> enabledWorkspaceIds() {
        return enabledWorkspaceStages().keySet();
    }

    @JsonIgnore
    @AssertTrue(message = "topics.enabledWorkspaces must list each workspace id as id or id:stage, with one stage per id and stage one of vectors, build, arrival, nightly, ui") public boolean isEnabledWorkspacesValid() {
        try {
            enabledWorkspaceStages();
            return true;
        } catch (IllegalArgumentException exception) {
            // The constraint message is fixed text: this names the entry.
            log.error("Topics config is not valid: {}", exception.getMessage());
            return false;
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

        /** How much longer than the rest the leader lock lasts, for the insert after the last embed call. */
        @NotNull @JsonProperty
        @MinDuration(value = 0, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 1, unit = TimeUnit.MINUTES)
        private Duration leaseSlack;

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

        /** Cut to the time left in the tick; adds to the leader lock's lease. At most {@code tickBudget}. */
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
