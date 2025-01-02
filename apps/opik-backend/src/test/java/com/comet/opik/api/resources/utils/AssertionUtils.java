package com.comet.opik.api.resources.utils;

import com.comet.opik.api.FeedbackScoreNames;
import lombok.experimental.UtilityClass;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@UtilityClass
public class AssertionUtils {

    public static void assertFeedbackScoreNames(FeedbackScoreNames actual, List<String> expectedNames) {
        assertThat(actual.scores()).hasSize(expectedNames.size());
        assertThat(actual
                .scores()
                .stream()
                .map(FeedbackScoreNames.ScoreName::name)
                .toList()).containsExactlyInAnyOrderElementsOf(expectedNames);
    }
}
