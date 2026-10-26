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

        @NotNull @JsonProperty
        @MinDuration(value = 1, unit = TimeUnit.SECONDS)
        private Duration longWindowInterval = Duration.days(1);

        /** The most traces one tick embeds, oldest first. */
        @JsonProperty
        @Min(1) @Max(100_000) private int maxTracesPerTick = 5_000;

        /** Texts per embed call; the service takes at most 256. */
        @JsonProperty
        @Min(1) @Max(256) private int embedBatchSize = 256;

        /** Each embed call's deadline, cut to the tick's. */
        @NotNull @JsonProperty
        @MinDuration(value = 100, unit = TimeUnit.MILLISECONDS)
        @MaxDuration(value = 60, unit = TimeUnit.SECONDS)
        private Duration embedTimeout = Duration.seconds(10);

        @JsonIgnore
        @AssertTrue(message = "topics.vectors.tickBudget must be shorter than topics.vectors.tickInterval") public boolean isTickBudgetValid() {
            return tickBudget == null || tickInterval == null
                    || tickBudget.toMilliseconds() < tickInterval.toMilliseconds();
        }

        @JsonIgnore
        @AssertTrue(message = "topics.vectors.longWindow must not be shorter than topics.vectors.window") public boolean isLongWindowValid() {
            return window == null || longWindow == null || longWindow.toMilliseconds() >= window.toMilliseconds();
        }
    }
}
