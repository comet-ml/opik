package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.util.List;

/**
 * @deprecated Use {@link FeedbackScoreNames} instead. This class is kept for backwards compatibility only.
 */
@Deprecated
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record ExperimentFeedbackScoreNames(List<ScoreName> scores) {

    public record ScoreName(String name, String type) {
    }
}
