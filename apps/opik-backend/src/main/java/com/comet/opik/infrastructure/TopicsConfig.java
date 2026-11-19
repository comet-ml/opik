package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.inference.InferenceClient;
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
import lombok.AccessLevel;
import lombok.Data;
import lombok.Getter;

import java.util.Arrays;
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

        /** Texts per embed call, up to the most the service takes in one. */
        @JsonProperty
        @Min(1) @Max(InferenceClient.MAX_EMBED_INPUTS) private int embedBatchSize;

        /** Embed calls in flight at a time; set it to the embed pool's replica count. */
        @JsonProperty
        @Min(1) private int embedConcurrency;

        /** Each embed call's deadline; a chunk starts only with at least this much of the tick budget left. */
        @NotNull @JsonProperty
        @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
        @MaxDuration(value = InferenceClient.MAX_EMBED_DEADLINE_SECONDS, unit = TimeUnit.SECONDS)
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

        /** Cut to the time left in the tick; lengthens the leader lease. At most {@code tickBudget}. */
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
