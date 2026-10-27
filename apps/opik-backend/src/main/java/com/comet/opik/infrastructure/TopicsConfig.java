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

import java.util.concurrent.TimeUnit;

/**
 * Topics: groups traces by the intent ai-labels summarizes. Step 1 only stores a bge-small vector of every summary, for
 * the workspaces ai-labels is enabled for. Off by default, and deliberately not a service toggle. Needs ai-labels on,
 * and calls the same opik-inference deployment with the same credential ({@code aiLabels.inference}); see
 * {@code OpikConfiguration#isTopicsAiLabelsEnabled}.
 */
@Data
public class TopicsConfig {

    @JsonProperty
    private boolean enabled = false;

    @Valid @NotNull @JsonProperty
    private VectorsConfig vectors = new VectorsConfig();

    @Data
    public static class VectorsConfig {

        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        private Duration tickInterval = Duration.minutes(1);

        /** Every ClickHouse query and embed call of a tick ends within this; what is left waits for the next tick. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        private Duration tickBudget = Duration.seconds(50);

        /** How far back, by status write time, each tick looks for labeled traces without a vector. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MINUTES)
        @MaxDuration(value = 30, unit = TimeUnit.DAYS)
        private Duration window = Duration.hours(24);

        /** The window of the pass run once per {@code longWindowInterval}, for rows the short window missed. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.MINUTES)
        @MaxDuration(value = 90, unit = TimeUnit.DAYS)
        private Duration longWindow = Duration.days(7);

        /**
         * How often the long-window pass starts. A pass stays on, tick after tick, until one of its ticks drains it;
         * this interval runs from that tick.
         */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        private Duration longWindowInterval = Duration.days(1);

        /**
         * When above zero, the long-window pass uses this window instead of {@code longWindow}, to catch up after a late
         * start or a re-enabled workspace. 0 is off. The find cost grows with it.
         */
        @NotNull @JsonProperty
        @MinDuration(value = 0, unit = TimeUnit.SECONDS)
        @MaxDuration(value = 365, unit = TimeUnit.DAYS)
        private Duration backfillWindow = Duration.seconds(0);

        /** The most traces one tick embeds per workspace, oldest first; the tick budget bounds the total. */
        @JsonProperty
        @Min(1) @Max(100_000) private int maxTracesPerTick = 5_000;

        /** Texts per embed call; the service takes at most 256. */
        @JsonProperty
        @Min(1) @Max(256) private int embedBatchSize = 256;

        /**
         * Each embed call's deadline. A chunk starts only with at least this much of the tick budget left, so a call
         * that starts always gets all of it. Must be under {@code tickBudget}.
         */
        @NotNull @JsonProperty
        @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
        @MaxDuration(value = 60, unit = TimeUnit.SECONDS)
        private Duration embedTimeout = Duration.seconds(10);

        @Valid @NotNull @JsonProperty
        private FindConfig find = new FindConfig();

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
        @Min(1) private long maxRowsToRead = 30_000_000L;

        @JsonProperty
        @Min(1) private long maxMemoryUsage = 1024L * 1024 * 1024;

        /** Cut to the time left in the tick; lengthens the leader lease. At most {@code tickBudget}. */
        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        private Duration maxExecutionTime = Duration.seconds(30);

        /**
         * ai-labels labels only traces created within {@code aiLabels.horizon}, so a status written at or after the
         * window's lower bound has {@code trace_created_at} at or after {@code lowerBound - horizon}. This margin covers
         * the time from the sweep to the status write (inference, load, retries) and a horizon changed since.
         */
        @NotNull @JsonProperty
        @MinDuration(value = 0, unit = TimeUnit.SECONDS)
        private Duration createdAtSlack = Duration.days(1);
    }
}
