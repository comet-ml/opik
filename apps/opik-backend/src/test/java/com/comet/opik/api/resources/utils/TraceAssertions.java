package com.comet.opik.api.resources.utils;

import com.comet.opik.api.Trace;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;
import java.util.List;

import static com.comet.opik.api.resources.utils.CommentAssertionUtils.assertComments;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

public class TraceAssertions {

    private static final String[] IGNORED_FIELDS_TRACES = {"projectId", "projectName", "createdAt",
            "lastUpdatedAt", "feedbackScores", "createdBy", "lastUpdatedBy", "totalEstimatedCost", "duration",
            "comments", "threadId"};

    private static final String[] IGNORED_FIELDS_SCORES = {"createdAt", "lastUpdatedAt", "createdBy", "lastUpdatedBy"};

    public static void assertErrorResponse(Response actualResponse, String message, int expectedStatus) {
        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        assertThat(actualResponse.hasEntity()).isTrue();
        assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class).getMessage())
                .isEqualTo(message);
    }

    public static void assertTraces(List<Trace> actualTraces, List<Trace> expectedTraces, String user) {
        assertTraces(actualTraces, expectedTraces, List.of(), user);
    }

    public static void assertTraces(List<Trace> actualTraces, List<Trace> expectedTraces, List<Trace> unexpectedTraces,
            String user) {

        assertThat(actualTraces)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_TRACES)
                .containsExactlyElementsOf(expectedTraces);

        assertIgnoredFields(actualTraces, expectedTraces, user);

        if (!unexpectedTraces.isEmpty()) {
            assertThat(actualTraces)
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_TRACES)
                    .doesNotContainAnyElementsOf(unexpectedTraces);
        }
    }

    private static void assertIgnoredFields(List<Trace> actualTraces, List<Trace> expectedTraces, String user) {
        assertThat(actualTraces).size().isEqualTo(expectedTraces.size());
        for (int i = 0; i < actualTraces.size(); i++) {
            var actualTrace = actualTraces.get(i);
            var expectedTrace = expectedTraces.get(i);
            assertIgnoredFields(actualTrace, expectedTrace, user);
        }
    }

    public static void assertIgnoredFields(Trace actualTrace, Trace expectedTrace, String user) {
        assertThat(actualTrace.projectId()).isNotNull();
        assertThat(actualTrace.projectName()).isNull();
        assertThat(actualTrace.createdAt()).isAfter(expectedTrace.createdAt());
        assertThat(actualTrace.lastUpdatedAt()).isAfter(expectedTrace.lastUpdatedAt());
        assertThat(actualTrace.createdBy()).isEqualTo(user);
        assertThat(actualTrace.lastUpdatedBy()).isEqualTo(user);
        assertThat(actualTrace.threadId()).isEqualTo(expectedTrace.threadId());

        var expected = DurationUtils.getDurationInMillisWithSubMilliPrecision(
                expectedTrace.startTime(), expectedTrace.endTime());

        if (actualTrace.duration() == null || expected == null) {
            assertThat(actualTrace.duration()).isEqualTo(expected);
        } else {
            assertThat(actualTrace.duration()).isEqualTo(expected, within(0.001));
        }

        assertThat(actualTrace.feedbackScores())
                .usingRecursiveComparison()
                .withComparatorForType(BigDecimal::compareTo, BigDecimal.class)
                .ignoringFields(IGNORED_FIELDS_SCORES)
                .ignoringCollectionOrder()
                .isEqualTo(expectedTrace.feedbackScores());

        if (expectedTrace.feedbackScores() != null) {
            actualTrace.feedbackScores().forEach(feedbackScore -> {
                assertThat(feedbackScore.createdAt()).isAfter(expectedTrace.createdAt());
                assertThat(feedbackScore.lastUpdatedAt()).isAfter(expectedTrace.lastUpdatedAt());
                assertThat(feedbackScore.createdBy()).isEqualTo(user);
                assertThat(feedbackScore.lastUpdatedBy()).isEqualTo(user);
            });
        }

        if (actualTrace.comments() != null) {
            assertComments(expectedTrace.comments(), actualTrace.comments());

            actualTrace.comments().forEach(comment -> {
                assertThat(comment.createdAt()).isAfter(actualTrace.createdAt());
                assertThat(comment.lastUpdatedAt()).isAfter(actualTrace.lastUpdatedAt());
                assertThat(comment.createdBy()).isEqualTo(user);
                assertThat(comment.lastUpdatedBy()).isEqualTo(user);
            });
        }

    }

}
