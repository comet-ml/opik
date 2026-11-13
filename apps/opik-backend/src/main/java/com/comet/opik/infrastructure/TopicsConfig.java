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
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;

import java.util.Arrays;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Topics: groups traces by the intent ai-labels summarizes. The vectors job stores a bge-small vector of every
 * summary, for the workspaces in {@code enabledWorkspaces} that ai-labels is enabled for; the first-build job builds
 * each project's topic list from them. Off by default, and deliberately not a service toggle. Needs ai-labels on, and
 * calls the same opik-inference deployment with the same credential ({@code aiLabels.inference}); see
 * {@code OpikConfiguration#isTopicsAiLabelsEnabled}. The defaults are in {@code config.yml}.
 */
@Data
public class TopicsConfig {

    @JsonProperty
    private boolean enabled;

    /**
     * The workspaces topics run for, comma-separated (Helm's {@code TOGGLE_TOPICS_WORKSPACES}). A workspace in it runs
     * only while it is also enabled in {@code ai_label_workspaces}: see {@code TopicWorkspaceService}. Empty (the
     * default): none.
     *
     * <p>Held as a String because Dropwizard substitutes env vars as scalars, so a comma-separated value cannot bind to
     * a collection. Nullable: null and blank both mean none. No getter, so the raw string cannot be matched against;
     * read it via {@link #enabledWorkspaceIds()}.
     */
    @JsonProperty
    @Getter(AccessLevel.NONE)
    private String enabledWorkspaces;

    @Valid @NotNull @JsonProperty
    private VectorsConfig vectors;

    @Valid @NotNull @JsonProperty
    private BuildConfig build;

    @Valid @NotNull @JsonProperty
    private NightlyConfig nightly;

    @Valid @NotNull @JsonProperty
    private FirstBuildConfig firstBuild;

    @Valid @NotNull @JsonProperty
    private ArrivalConfig arrival;

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

    /**
     * Topic on arrival: after the vectors tick inserts a chunk of vectors, each trace of a project with a saved list gets
     * the topic of its nearest saved prototype in {@code trace_topics}, until the next run writes its own row.
     */
    @Data
    public static class ArrivalConfig {

        /** Runs only when {@code topics.enabled} is on too. Off: the tick reads no list and writes no rows. */
        @JsonProperty
        private boolean enabled;

        /**
         * All the arrival steps of one vectors tick (the prototypes read and the insert after each vectors insert) end
         * within this, in total.
         */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 60, unit = TimeUnit.SECONDS)
        private Duration timeout;
    }

    /**
     * How one project's topic list is built: the window it reads, how long a build may run, and how long its
     * {@code topic_runs} rows are kept.
     */
    @Data
    public static class BuildConfig {

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

        /** The deadline of the Qwen call that names the groups. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 300, unit = TimeUnit.SECONDS)
        private Duration qwenDeadline;

        /** The deadline of a nightly update's naming calls ({@code naming}, {@code naming_new}). */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 300, unit = TimeUnit.SECONDS)
        private Duration namingDeadline;

        /** The deadline of a nightly update's verify call. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 300, unit = TimeUnit.SECONDS)
        private Duration verifyDeadline;

        /** {@code topic_runs} rows are deleted after this. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.DAYS)
        @MaxDuration(value = 365, unit = TimeUnit.DAYS)
        private Duration runsRetention;

        @JsonIgnore
        @AssertTrue(message = "topics.build.qwenDeadline must be shorter than topics.build.projectTimeout") public boolean isQwenDeadlineValid() {
            return qwenDeadline == null || projectTimeout == null
                    || qwenDeadline.toMilliseconds() < projectTimeout.toMilliseconds();
        }

        @JsonIgnore
        @AssertTrue(message = "topics.build.namingDeadline must be shorter than topics.build.projectTimeout") public boolean isNamingDeadlineValid() {
            return namingDeadline == null || projectTimeout == null
                    || namingDeadline.toMilliseconds() < projectTimeout.toMilliseconds();
        }

        @JsonIgnore
        @AssertTrue(message = "topics.build.verifyDeadline must be shorter than topics.build.projectTimeout") public boolean isVerifyDeadlineValid() {
            return verifyDeadline == null || projectTimeout == null
                    || verifyDeadline.toMilliseconds() < projectTimeout.toMilliseconds();
        }

        /** A sample of {@code maxVectors} traces must still hold the {@code minVectors} a build needs. */
        @JsonIgnore
        @AssertTrue(message = "topics.build.maxVectors must be at least topics.build.minVectors") public boolean isMaxVectorsValid() {
            return maxVectors >= minVectors;
        }
    }

    /**
     * The first-build job: builds the list of a project that has none yet. Its window ends at tomorrow's start (UTC), so
     * it holds today's traces so far. A project whose first builds failed waits 1 hour, then 6 hours, then a day between
     * attempts.
     */
    @Data
    public static class NightlyConfig {

        /** Runs only when {@code topics.enabled} is on too. */
        @JsonProperty
        private boolean enabled;

        /** Quartz cron, UTC. The night is the UTC date the job starts on. */
        @NotBlank @JsonProperty
        private String schedule;

        /** No project starts after this much of the night has passed; the rest run the next night. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MINUTES)
        @MaxDuration(value = 20, unit = TimeUnit.HOURS)
        private Duration budget;
    }

    @Data
    public static class FirstBuildConfig {

        /** Runs only when {@code topics.enabled} is on too. */
        @JsonProperty
        private boolean enabled;

        /** How often every pod fires the job; one run per interval across the cluster. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MINUTES)
        @MaxDuration(value = 1, unit = TimeUnit.DAYS)
        private Duration interval;

        /** No project starts after this much of a run has passed; the rest wait for the next run. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MINUTES)
        @MaxDuration(value = 1, unit = TimeUnit.DAYS)
        private Duration budget;

        /**
         * A project's first build waits for its vectors backfill to drain. Past this long since the backfill started,
         * it is built anyway once at least 99% of the backfill's traces have a vector; with fewer it keeps waiting.
         */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MINUTES)
        @MaxDuration(value = 30, unit = TimeUnit.DAYS)
        private Duration backfillMaxWait;

        @JsonIgnore
        @AssertTrue(message = "topics.firstBuild.budget must be shorter than topics.firstBuild.interval") public boolean isBudgetValid() {
            return budget == null || interval == null || budget.toMilliseconds() < interval.toMilliseconds();
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

        /** The window of the daily pass, for rows the fresh phase missed, and of a project's backfill. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MINUTES)
        @MaxDuration(value = 90, unit = TimeUnit.DAYS)
        private Duration longWindow;

        /**
         * How often the daily long-window pass starts. A pass stays on, every second tick, until one of its ticks drains
         * it; this interval runs from that tick. Also how often the tick discovers each workspace's projects for the
         * backfill.
         */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 7, unit = TimeUnit.DAYS)
        private Duration longWindowInterval;

        /**
         * When above zero, the backfill phase and the daily pass use this window instead of {@code longWindow}, to embed
         * the traces summarised before topics were enabled. 0 is off.
         */
        @NotNull @JsonProperty
        @MinDuration(value = 0, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 365, unit = TimeUnit.DAYS)
        private Duration backfillWindow;

        /**
         * The most traces one tick embeds per workspace in the fresh phase or the daily pass, and per project in the
         * backfill phase; the tick budget bounds the total.
         */
        @JsonProperty
        @Min(1) @Max(100_000) private int maxTracesPerTick;

        /** Texts per embed call; the service takes at most 256. */
        @JsonProperty
        @Min(1) @Max(256) private int embedBatchSize;

        /**
         * Embed calls in flight at a time, within one workspace's or project's read. The embed pool computes one call
         * at a time per replica, so this should equal its replica count: more only makes each call wait longer, toward
         * {@code embedTimeout}. At most 4: the service's queue holds 4 full calls and answers a fifth with a 429.
         */
        @JsonProperty
        @Min(1) @Max(4) private int embedConcurrency = 1;

        /**
         * Each embed call's deadline. A chunk starts only with at least this much of the tick budget left, so a call
         * that starts always gets all of it. Must be under {@code tickBudget}.
         */
        @NotNull @JsonProperty
        @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
        @MaxDuration(value = 60, unit = TimeUnit.SECONDS)
        private Duration embedTimeout;

        @Valid @NotNull @JsonProperty
        private FindConfig find;

        @JsonIgnore
        @AssertTrue(message = "topics.vectors.tickBudget must be shorter than topics.vectors.tickInterval") public boolean isTickBudgetValid() {
            return tickBudget == null || tickInterval == null
                    || tickBudget.toMilliseconds() < tickInterval.toMilliseconds();
        }

        @JsonIgnore
        @AssertTrue(message = "topics.vectors.longWindow must not be shorter than topics.vectors.window") public boolean isLongWindowValid() {
            return window == null || longWindow == null || longWindow.toMilliseconds() >= window.toMilliseconds();
        }

        @JsonIgnore
        @AssertTrue(message = "topics.vectors.embedTimeout must be shorter than topics.vectors.tickBudget") public boolean isEmbedTimeoutValid() {
            return embedTimeout == null || tickBudget == null
                    || embedTimeout.toMilliseconds() < tickBudget.toMilliseconds();
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

    /**
     * Guards of the window read on the shared ClickHouse, as {@code aiLabels.sweep}'s. A trip fails that workspace's read
     * for the tick.
     */
    @Data
    public static class FindConfig {

        @JsonProperty
        @Min(1) @Max(1_000_000_000L) private long maxRowsToRead;

        @JsonProperty
        @Min(1) @Max(8L * 1024 * 1024 * 1024) private long maxMemoryUsage;

        /** Cut to the time left in the tick; lengthens the leader lease. At most {@code tickBudget}. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        private Duration maxExecutionTime;

        /**
         * ai-labels labels only traces created within {@code aiLabels.horizon}, so a status written at or after the
         * window's lower bound has {@code trace_created_at} at or after {@code lowerBound - horizon}. This margin covers
         * the time from the sweep to the status write (inference, load, retries) and a horizon changed since.
         */
        @NotNull @JsonProperty
        @MinDuration(value = 0, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 7, unit = TimeUnit.DAYS)
        private Duration createdAtSlack;

        /**
         * The daily pass, and the backfill of a large project, read their window in slices of this much trace creation
         * time, one query each, so a query's rows and memory follow the slice and not the window.
         */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.HOURS)
        @MaxDuration(value = 365, unit = TimeUnit.DAYS)
        private Duration sliceSize;
    }
}
