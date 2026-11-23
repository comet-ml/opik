package com.comet.opik.api.resources.utils;

import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemStreamRequest;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentTestAssertions;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;
import org.apache.commons.lang3.RandomStringUtils;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Checks that a batch-created and a single-created trace store the same slim input and output: both are read back
 * through the experiment items stream with {@code truncate=true}, which reads the trace slim columns.
 */
@Builder(toBuilder = true)
public record SlimPayloadStorageCheck(
        @NonNull TraceResourceClient traceResourceClient,
        @NonNull ProjectResourceClient projectResourceClient,
        @NonNull ExperimentResourceClient experimentResourceClient,
        @NonNull PodamFactory factory,
        @NonNull String apiKey,
        @NonNull String workspaceName,
        @NonNull String user) {

    public void assertBatchAndSingleCreateStoreTheSameSlimPayload() {
        var slimCase = PayloadCases.slimCase();
        var projectName = "project-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var batchTrace = trace(projectName, slimCase.payload());
        var singleTrace = trace(projectName, slimCase.payload());
        traceResourceClient.batchCreateTraces(List.of(batchTrace), apiKey, workspaceName);
        traceResourceClient.createTrace(singleTrace, apiKey, workspaceName);

        var projectId = projectResourceClient.getByName(projectName, apiKey, workspaceName).id();
        var experiment = experimentResourceClient.createPartialExperiment().build();
        experimentResourceClient.create(experiment, apiKey, workspaceName);
        var items = Stream.of(batchTrace, singleTrace)
                .map(trace -> factory.manufacturePojo(ExperimentItem.class).toBuilder()
                        .experimentId(experiment.id())
                        .traceId(trace.id())
                        .projectId(projectId)
                        .projectName(projectName)
                        .totalEstimatedCost(null)
                        .usage(null)
                        .duration(DurationUtils.getDurationInMillisWithSubMilliPrecision(
                                trace.startTime(), trace.endTime()))
                        .executionPolicy(ExecutionPolicy.DEFAULT)
                        .build())
                .toList();
        experimentResourceClient.createExperimentItem(Set.copyOf(items), apiKey, workspaceName);

        var actualItems = experimentResourceClient.streamExperimentItems(
                ExperimentItemStreamRequest.builder().experimentName(experiment.name()).truncate(true).build(),
                apiKey, workspaceName);

        var expectedItems = items.stream()
                .map(item -> item.toBuilder()
                        .input(slimCase.slim())
                        .output(slimCase.slim())
                        .feedbackScores(null)
                        .comments(null)
                        .createdBy(user)
                        .lastUpdatedBy(user)
                        .build())
                .sorted(Comparator.comparing(ExperimentItem::id).reversed())
                .toList();
        ExperimentTestAssertions.assertExperimentResults(actualItems, expectedItems, user);
    }

    private Trace trace(String projectName, JsonNode payload) {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .usage(null)
                .feedbackScores(null)
                .input(payload)
                .output(payload)
                .build();
    }
}
