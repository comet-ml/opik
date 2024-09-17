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
        boolean expectedOutput = Stream
                .of("createDataset", "updateDataset", "deleteDataset", "deleteDatasetByName", "createDatasetItems",
                        "deleteDatasetItems")
                .allMatch(methodName -> {
                    List<Method> methods = Arrays.stream(DatasetsResource.class.getMethods())
                            .filter(method -> method.getName().equals(methodName))
                            .toList();

                    return !methods.isEmpty() && methods.stream()
                            .allMatch(method -> method.isAnnotationPresent(RateLimited.class));
                });

        // Then
        Assertions.assertTrue(expectedOutput);
    }

    @Test
    void allEventFromExperimentsResourceShouldBeRateLimited() {

        // Given
        boolean expectedOutput = Stream.of("create", "createExperimentItems", "deleteExperimentItems")
                .allMatch(methodName -> {
                    List<Method> methods = Arrays.stream(ExperimentsResource.class.getMethods())
                            .filter(method -> method.getName().equals(methodName))
                            .toList();

                    return !methods.isEmpty() && methods.stream()
                            .allMatch(method -> method.isAnnotationPresent(RateLimited.class));
                });
        // Then
        Assertions.assertTrue(expectedOutput);
    }

    @Test
    void allEventFromFeedbackResourceShouldBeRateLimited() {

        // Given
        boolean expectedOutput = Stream.of("create", "update", "deleteById")
                .allMatch(methodName -> {
                    List<Method> methods = Arrays.stream(FeedbackDefinitionResource.class.getMethods())
                            .filter(method -> method.getName().equals(methodName))
                            .toList();

                    return !methods.isEmpty() && methods.stream()
                            .allMatch(method -> method.isAnnotationPresent(RateLimited.class));
                });
        // Then
        Assertions.assertTrue(expectedOutput);
    }

    @Test
    void allEventFromProjectsResourceShouldBeRateLimited() {

        // Given
        boolean expectedOutput = Stream.of("create", "update", "deleteById")
                .allMatch(methodName -> {
                    List<Method> methods = Arrays.stream(ProjectsResource.class.getMethods())
                            .filter(method -> method.getName().equals(methodName))
                            .toList();

                    return !methods.isEmpty() && methods.stream()
                            .allMatch(method -> method.isAnnotationPresent(RateLimited.class));
                });

        // Then
        Assertions.assertTrue(expectedOutput);
    }

    @Test
    void allEventFromSpansResourceShouldBeRateLimited() {

        // Given
        boolean expectedOutput = Stream
                .of("create", "createSpans", "update", "deleteById", "addSpanFeedbackScore", "deleteSpanFeedbackScore",
                        "scoreBatchOfSpans")
                .allMatch(methodName -> {
                    List<Method> methods = Arrays.stream(SpansResource.class.getMethods())
                            .filter(method -> method.getName().equals(methodName))
                            .toList();

                    return !methods.isEmpty() && methods.stream()
                            .allMatch(method -> method.isAnnotationPresent(RateLimited.class));
                });

        // Then
        Assertions.assertTrue(expectedOutput);
    }

    @Test
    void allEventFromTracesResourceShouldBeRateLimited() {

        // Given
        boolean expectedOutput = Stream
                .of("create", "createTraces", "update", "deleteById", "deleteTraces", "addTraceFeedbackScore",
                        "deleteTraceFeedbackScore", "scoreBatchOfTraces")
                .allMatch(methodName -> {
                    List<Method> methods = Arrays.stream(TracesResource.class.getMethods())
                            .filter(method -> method.getName().equals(methodName))
                            .toList();

                    return !methods.isEmpty() && methods.stream()
                            .allMatch(method -> method.isAnnotationPresent(RateLimited.class));
                });

        // Then
        Assertions.assertTrue(expectedOutput);
    }

}
