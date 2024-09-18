package com.comet.opik.infrastructure.ratelimit;

import com.comet.opik.api.resources.v1.priv.DatasetsResource;
import com.comet.opik.api.resources.v1.priv.ExperimentsResource;
import com.comet.opik.api.resources.v1.priv.FeedbackDefinitionResource;
import com.comet.opik.api.resources.v1.priv.ProjectsResource;
import com.comet.opik.api.resources.v1.priv.SpansResource;
import com.comet.opik.api.resources.v1.priv.TracesResource;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

class RateLimitSetupTest {

    @Test
    void allEventFromDatasetsResourceShouldBeRateLimited() {

        // Given
        Stream.of("createDataset", "updateDataset", "createDatasetItems")
                .forEach(methodName -> {
                    // Then
                    assertIfMethodAreAnnotated(methodName, DatasetsResource.class);
                });
    }

    @Test
    void allEventFromExperimentsResourceShouldBeRateLimited() {

        // Given
        Stream.of("create", "createExperimentItems")
                .forEach(methodName -> {
                    // Then
                    assertIfMethodAreAnnotated(methodName, ExperimentsResource.class);
                });
    }

    private void assertIfMethodAreAnnotated(String methodName, Class<?> targetClass) {
        List<Method> targetMethods = Arrays.stream(targetClass.getMethods())
                .filter(method -> method.getName().equals(methodName))
                .toList();

        boolean actualMatch = !targetMethods.isEmpty() && targetMethods.stream()
                .allMatch(method -> method.isAnnotationPresent(RateLimited.class));

        Assertions.assertTrue(actualMatch,
                "Method %s.%s is not annotated".formatted(targetClass.getSimpleName(), methodName));
    }

    @Test
    void allEventFromFeedbackResourceShouldBeRateLimited() {

        // Given
        Stream.of("create", "update")
                .forEach(methodName -> {
                    // Then
                    assertIfMethodAreAnnotated(methodName, FeedbackDefinitionResource.class);
                });
    }

    @Test
    void allEventFromProjectsResourceShouldBeRateLimited() {

        // Given
        Stream.of("create", "update")
                .forEach(methodName -> {
                    // Then
                    assertIfMethodAreAnnotated(methodName, ProjectsResource.class);
                });
    }

    @Test
    void allEventFromSpansResourceShouldBeRateLimited() {

        // Given
        Stream.of("create", "createSpans", "update", "addSpanFeedbackScore", "scoreBatchOfSpans")
                .forEach(methodName -> {
                    // Then
                    assertIfMethodAreAnnotated(methodName, SpansResource.class);
                });
    }

    @Test
    void allEventFromTracesResourceShouldBeRateLimited() {

        // Given
        Stream.of("create", "createTraces", "update", "addTraceFeedbackScore", "scoreBatchOfTraces")
                .forEach(methodName -> {
                    // Then
                    assertIfMethodAreAnnotated(methodName, TracesResource.class);
                });
    }

}
