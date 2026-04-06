package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.EvaluationMethod;
import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.ExperimentExecutionResponse;
import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Singleton
@Slf4j
public class ExperimentExecutionService {

    /** Default fallback; prefer dataset-specific value when available */
    private static final int DEFAULT_RUNS_PER_ITEM = 1;
    private static final int MAX_CONCURRENT_ITEMS = 5;
    private static final String PLAYGROUND_PROJECT_NAME = "playground";

    private final ExperimentService experimentService;
    private final DatasetItemService datasetItemService;
    private final DatasetVersionService datasetVersionService;
    private final ExperimentItemProcessor itemProcessor;
    private final IdGenerator idGenerator;
    private final ExecutorService executorService;

    @Inject
    public ExperimentExecutionService(
            @NonNull ExperimentService experimentService,
            @NonNull DatasetItemService datasetItemService,
            @NonNull DatasetVersionService datasetVersionService,
            @NonNull ExperimentItemProcessor itemProcessor,
            @NonNull IdGenerator idGenerator) {
        this.experimentService = experimentService;
        this.datasetItemService = datasetItemService;
        this.datasetVersionService = datasetVersionService;
        this.itemProcessor = itemProcessor;
        this.idGenerator = idGenerator;
        this.executorService = Executors.newFixedThreadPool(MAX_CONCURRENT_ITEMS);
    }

    /**
     * Creates experiments and dispatches async processing.
     * Returns immediately with experiment IDs so the caller can start polling.
     */
    public ExperimentExecutionResponse createAndExecute(
            @NonNull ExperimentExecutionRequest request,
            @NonNull String workspaceId,
            @NonNull String userName) {

        String projectName = request.projectName() != null ? request.projectName() : PLAYGROUND_PROJECT_NAME;

        List<DatasetItem> datasetItems = fetchAllDatasetItems(request, workspaceId, userName);

        if (datasetItems.isEmpty()) {
            log.warn("No dataset items found for dataset '{}', workspaceId '{}'",
                    request.datasetName(), workspaceId);
            return ExperimentExecutionResponse.builder()
                    .experiments(List.of())
                    .totalItems(0)
                    .build();
        }

        if (request.datasetId() == null) {
            throw new BadRequestException("Dataset ID is required for experiment execution");
        }

        ExecutionPolicy datasetExecutionPolicy = fetchDatasetExecutionPolicy(
                request.datasetId(), request.versionHash(), workspaceId);

        List<ExperimentExecutionResponse.ExperimentInfo> experimentInfos = new ArrayList<>();
        List<UUID> experimentIds = new ArrayList<>();

        for (int i = 0; i < request.prompts().size(); i++) {
            var prompt = request.prompts().get(i);
            UUID experimentId = idGenerator.generateId();

            ObjectNode metadata = JsonUtils.createObjectNode();
            metadata.put("model", prompt.model());
            metadata.set("messages", JsonUtils.getMapper().valueToTree(prompt.messages()));
            if (prompt.configs() != null) {
                metadata.set("model_config", JsonUtils.getMapper().valueToTree(prompt.configs()));
            }

            var experiment = Experiment.builder()
                    .id(experimentId)
                    .datasetName(request.datasetName())
                    .datasetVersionId(request.datasetVersionId())
                    .projectName(projectName)
                    .metadata(metadata)
                    .evaluationMethod(EvaluationMethod.EVALUATION_SUITE)
                    .status(ExperimentStatus.RUNNING)
                    .promptVersions(
                            prompt.promptVersions() != null ? prompt.promptVersions() : request.promptVersions())
                    .build();

            experimentService.create(experiment)
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, workspaceId)
                            .put(RequestContext.USER_NAME, userName)
                            .put(RequestContext.VISIBILITY, com.comet.opik.api.Visibility.PRIVATE))
                    .block();

            experimentIds.add(experimentId);
            experimentInfos.add(ExperimentExecutionResponse.ExperimentInfo.builder()
                    .experimentId(experimentId)
                    .promptIndex(i)
                    .build());
        }

        int totalItems = calculateTotalItems(datasetItems, datasetExecutionPolicy, request.prompts().size());

        dispatchAsyncProcessing(request, datasetItems, experimentIds,
                datasetExecutionPolicy, projectName, workspaceId, userName);

        log.info("Created '{}' experiments with '{}' total items for dataset '{}', workspaceId '{}'",
                experimentIds.size(), totalItems, request.datasetName(), workspaceId);

        return ExperimentExecutionResponse.builder()
                .experiments(experimentInfos)
                .totalItems(totalItems)
                .build();
    }

    private List<DatasetItem> fetchAllDatasetItems(ExperimentExecutionRequest request,
            String workspaceId, String userName) {
        var streamRequest = DatasetItemStreamRequest.builder()
                .datasetName(request.datasetName())
                .datasetVersion(request.versionHash())
                .build();

        return datasetItemService.getItems(workspaceId, streamRequest, List.of())
                .collectList()
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.VISIBILITY, com.comet.opik.api.Visibility.PRIVATE))
                .block();
    }

    private ExecutionPolicy fetchDatasetExecutionPolicy(UUID datasetId, String versionHash, String workspaceId) {
        try {
            if (versionHash != null) {
                var versionId = datasetVersionService.resolveVersionId(workspaceId, datasetId, versionHash);
                var version = datasetVersionService.getVersionById(workspaceId, datasetId, versionId);
                return version.executionPolicy();
            }
            return datasetVersionService.getLatestVersion(datasetId, workspaceId)
                    .map(v -> v.executionPolicy())
                    .orElse(null);
        } catch (Exception e) {
            log.warn("Failed to fetch dataset execution policy for dataset '{}'", datasetId, e);
            return null;
        }
    }

    private int calculateTotalItems(List<DatasetItem> items, ExecutionPolicy versionPolicy, int promptCount) {
        int total = 0;
        for (DatasetItem item : items) {
            int runsPerItem = getEffectiveRunsPerItem(item.executionPolicy(), versionPolicy);
            total += runsPerItem;
        }
        return total * promptCount;
    }

    private int getEffectiveRunsPerItem(ExecutionPolicy itemPolicy, ExecutionPolicy versionPolicy) {
        if (itemPolicy != null && itemPolicy.runsPerItem() > 0) {
            return itemPolicy.runsPerItem();
        }
        if (versionPolicy != null && versionPolicy.runsPerItem() > 0) {
            return versionPolicy.runsPerItem();
        }
        return DEFAULT_RUNS_PER_ITEM;
    }

    private void dispatchAsyncProcessing(
            ExperimentExecutionRequest request,
            List<DatasetItem> datasetItems,
            List<UUID> experimentIds,
            ExecutionPolicy datasetExecutionPolicy,
            String projectName,
            String workspaceId,
            String userName) {

        var futures = new ArrayList<CompletableFuture<Void>>();

        for (DatasetItem item : datasetItems) {
            int runsPerItem = getEffectiveRunsPerItem(item.executionPolicy(), datasetExecutionPolicy);

            for (int run = 0; run < runsPerItem; run++) {
                for (int promptIdx = 0; promptIdx < request.prompts().size(); promptIdx++) {
                    var prompt = request.prompts().get(promptIdx);
                    UUID experimentId = experimentIds.get(promptIdx);

                    futures.add(CompletableFuture.runAsync(() -> {
                        try {
                            itemProcessor.process(
                                    prompt, item, experimentId,
                                    request.datasetId(), request.versionHash(),
                                    projectName, workspaceId, userName);
                        } catch (Exception e) {
                            log.error("Failed to process item '{}' for experiment '{}'",
                                    item.id(), experimentId, e);
                        }
                    }, executorService));
                }
            }
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((v, ex) -> {
                    if (ex != null) {
                        log.error("Unexpected error during experiment processing", ex);
                    }
                    finishExperiments(experimentIds, workspaceId, userName);
                });
    }

    /** userName is required for audit context in the reactive pipeline */
    private void finishExperiments(List<UUID> experimentIds, String workspaceId, String userName) {
        try {
            java.util.function.Function<reactor.util.context.Context, reactor.util.context.Context> reactorContext = ctx -> ctx
                    .put(RequestContext.WORKSPACE_ID, workspaceId)
                    .put(RequestContext.USER_NAME, userName)
                    .put(RequestContext.WORKSPACE_NAME, workspaceId)
                    .put(RequestContext.VISIBILITY, com.comet.opik.api.Visibility.PRIVATE);

            var statusUpdate = com.comet.opik.api.ExperimentUpdate.builder()
                    .status(ExperimentStatus.COMPLETED)
                    .build();

            for (UUID experimentId : experimentIds) {
                experimentService.update(experimentId, statusUpdate)
                        .contextWrite(reactorContext)
                        .block();
            }

            experimentService.finishExperiments(Set.copyOf(experimentIds))
                    .contextWrite(reactorContext)
                    .block();
            log.info("Finished '{}' experiments", experimentIds.size());
        } catch (Exception e) {
            log.error("Failed to finish experiments", e);
        }
    }
}
