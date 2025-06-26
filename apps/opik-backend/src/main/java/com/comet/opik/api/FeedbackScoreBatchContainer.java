package com.comet.opik.api;

import com.comet.opik.infrastructure.ratelimit.RateEventContainer;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;

import java.beans.ConstructorProperties;
import java.util.List;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;

@SuperBuilder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
@Accessors(fluent = true, chain = true)
@Getter(onMethod_ = {@JsonProperty})
public abstract class FeedbackScoreBatchContainer<T extends FeedbackScoreItem> implements RateEventContainer {

    @NotNull @Size(min = 1, max = 1000) private List<@Valid T> scores;

    @ConstructorProperties({"scores"})
    protected FeedbackScoreBatchContainer(List<T> scores) {
        this.scores = scores;
    }

    @Override
    public long eventCount() {
        return scores.size();
    }

    @ToString(callSuper = true)
    @EqualsAndHashCode(callSuper = true)
    @SuperBuilder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Accessors(fluent = true)
    @Getter
    public static class FeedbackScoreBatch extends FeedbackScoreBatchContainer<FeedbackScoreBatchItem> {

        @ConstructorProperties({"scores"})
        public FeedbackScoreBatch(List<FeedbackScoreBatchItem> scores) {
            super(scores);
        }

    }

    @ToString(callSuper = true)
    @EqualsAndHashCode(callSuper = true)
    @SuperBuilder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Accessors(fluent = true)
    @Getter
    public static class FeedbackScoreBatchThread extends FeedbackScoreBatchContainer<FeedbackScoreBatchItemThread> {

        @ConstructorProperties({"scores"})
        public FeedbackScoreBatchThread(List<FeedbackScoreBatchItemThread> scores) {
            super(scores);
        }
    }

}
