package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.resources.utils.CommentAssertionUtils;
import com.comet.opik.api.resources.utils.StatsUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

public class ExperimentTestAssertions {

    public static final String[] EXPERIMENT_ITEMS_IGNORED_FIELDS = {"createdAt", "lastUpdatedAt",
            "feedbackScores.createdAt", "feedbackScores.lastUpdatedAt", "comments.createdAt", "comments.lastUpdatedAt",
            "feedbackScores.valueByAuthor", "projectName"
    };

    public static final String[] EXPERIMENT_IGNORED_FIELDS = new String[]{
            "id", "datasetId", "name", "feedbackScores", "assertionScores", "traceCount", "createdAt",
            "lastUpdatedAt", "createdBy", "lastUpdatedBy", "comments", "projectName", "datasetItemCount"};

    /**
     * Asserts that {@code actual} matches {@code expected} via recursive comparison ignoring
     * {@link #EXPERIMENT_IGNORED_FIELDS}, plus per-field equality on every ignored scalar
     * <em>except</em> {@code lastUpdatedAt} and the async-aggregation fields. {@code lastUpdatedAt}
     * is left to the caller because its expected semantic is scenario-specific ({@code ==} for an
     * untouched experiment, {@code isAfter} for one re-written by a job). Aggregation fields
     * ({@code feedbackScores}/{@code traceCount}/etc.) are also async and out of this helper's
     * scope. Fields that <em>aren't</em> in {@code EXPERIMENT_IGNORED_FIELDS} (notably
     * {@code projectId}) are covered by the recursive comparison, so no explicit re-assertion is
     * needed for them here.
     */
    public static void assertExperimentEqual(Experiment actual, Experiment expected) {
        assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields(EXPERIMENT_IGNORED_FIELDS)
                .isEqualTo(expected);

        assertThat(actual.id()).isEqualTo(expected.id());
        assertThat(actual.datasetId()).isEqualTo(expected.datasetId());
        assertThat(actual.name()).isEqualTo(expected.name());
        assertThat(actual.createdAt()).isEqualTo(expected.createdAt());
        assertThat(actual.createdBy()).isEqualTo(expected.createdBy());
        assertThat(actual.lastUpdatedBy()).isEqualTo(expected.lastUpdatedBy());
        assertThat(actual.projectName()).isEqualTo(expected.projectName());
    }

    public static void assertExperimentResultsIgnoringFields(List<ExperimentItem> actual, List<ExperimentItem> expected,
            String[] ignoringFields) {
        assertThat(actual).hasSize(expected.size());

        assertThat(actual)
                .usingRecursiveComparison()
                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "duration")
                .ignoringCollectionOrderInFields("feedbackScores", "comments")
                .ignoringFields(ignoringFields)
                .isEqualTo(expected);

        if (ignoringFields != null && Set.of(ignoringFields).contains("traceId")) {
            assertThat(actual.getFirst().traceId()).isNotNull();
        }
    }

    public static void assertExperimentResults(ExperimentItem actualExperimentItems,
            ExperimentItem expectedExperimentItems, String userName) {
        assertExperimentResults(List.of(actualExperimentItems), List.of(expectedExperimentItems), userName);
    }

    public static void assertExperimentResults(List<ExperimentItem> actualExperimentItems,
            List<ExperimentItem> expectedExperimentItems,
            String userName) {
        assertExperimentResults(actualExperimentItems, expectedExperimentItems, List.of(), userName);
    }

    public static void assertExperimentResults(List<ExperimentItem> actualExperimentItems,
            List<ExperimentItem> expectedExperimentItems,
            List<ExperimentItem> unexpectedExperimentItems,
            String userName) {

        assertThat(actualExperimentItems)
                .usingRecursiveComparison()
                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "duration")
                .ignoringCollectionOrderInFields("feedbackScores", "comments")
                .ignoringFields(EXPERIMENT_ITEMS_IGNORED_FIELDS)
                .isEqualTo(expectedExperimentItems);

        assertIgnoredFields(actualExperimentItems, expectedExperimentItems, userName);

        if (!unexpectedExperimentItems.isEmpty()) {
            assertThat(actualExperimentItems)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(EXPERIMENT_ITEMS_IGNORED_FIELDS)
                    .doesNotContainAnyElementsOf(unexpectedExperimentItems);
        }
    }

    private static void assertIgnoredFields(
            List<ExperimentItem> actualExperimentItems, List<ExperimentItem> expectedExperimentItems, String userName) {
        assertThat(actualExperimentItems).hasSameSizeAs(expectedExperimentItems);
        for (int i = 0; i < actualExperimentItems.size(); i++) {
            assertIgnoredFields(actualExperimentItems.get(i), expectedExperimentItems.get(i), userName);
        }
    }

    private static void assertIgnoredFields(ExperimentItem actualExperimentItem, ExperimentItem expectedExperimentItem,
            String userName) {
        assertThat(actualExperimentItem.createdAt()).isAfter(expectedExperimentItem.createdAt());
        assertThat(actualExperimentItem.lastUpdatedAt()).isAfter(expectedExperimentItem.lastUpdatedAt());
        assertThat(actualExperimentItem.createdBy()).isEqualTo(userName);
        assertThat(actualExperimentItem.lastUpdatedBy()).isEqualTo(userName);

        if (expectedExperimentItem.feedbackScores() != null && actualExperimentItem.feedbackScores() != null) {
            assertThat(actualExperimentItem.feedbackScores()).hasSize(expectedExperimentItem.feedbackScores().size());

            for (int i = 0; i < actualExperimentItem.feedbackScores().size(); i++) {
                FeedbackScore actualScore = actualExperimentItem.feedbackScores().get(i);
                FeedbackScore expectedScore = expectedExperimentItem.feedbackScores().get(i);

                assertThat(actualScore.createdAt()).isAfter(expectedScore.createdAt());
                assertThat(actualScore.lastUpdatedAt()).isAfter(expectedScore.lastUpdatedAt());
            }
        }

        if (expectedExperimentItem.comments() != null && actualExperimentItem.comments() != null) {

            assertThat(actualExperimentItem.comments()).hasSize(expectedExperimentItem.comments().size());
            CommentAssertionUtils.assertComments(expectedExperimentItem.comments(), actualExperimentItem.comments());

            for (int i = 0; i < actualExperimentItem.comments().size(); i++) {

                var actualComment = actualExperimentItem.comments().get(i);
                var expectedComment = expectedExperimentItem.comments().get(i);

                assertThat(actualComment.createdAt()).isAfter(expectedComment.createdAt());
                assertThat(actualComment.lastUpdatedAt()).isAfter(expectedComment.lastUpdatedAt());
            }
        }

    }
}
