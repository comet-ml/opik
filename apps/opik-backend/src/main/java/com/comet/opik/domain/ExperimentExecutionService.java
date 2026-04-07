package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.EvaluationMethod;
import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.ExperimentExecutionResponse;
import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.infrastructure.EvalSuiteConfig;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

@Singleton
@Slf4j
public class ExperimentExecutionService {

    private final ExperimentService experimentService;
    private final DatasetItemService datasetItemService;
    private final DatasetVersionService datasetVersionService;
    private final ExperimentItemProcessor itemProcessor;
    private final IdGenerator idGenerator;
    private final EvalSuiteConfig evalSuiteConfig;
    private final ExperimentExecutionConfig experimentExecutionConfig;
    private final ExecutorService executorService;

    @Inject
    public ExperimentExecutionService(
            @NonNull ExperimentService experimentService,
            @NonNull DatasetItemService datasetItemService,
            @NonNull DatasetVersionService datasetVersionService,
            @NonNull ExperimentItemProcessor itemProcessor,
            @NonNull IdGenerator idGenerator,
            @NonNull @Config("evalSuite") EvalSuiteConfig evalSuiteConfig,
            @NonNull @Config("experimentExecution") ExperimentExecutionConfig experimentExecutionConfig) {
        this.experimentService = experimentService;
        this.datasetItemService = datasetItemService;
        this.datasetVersionService = datasetVersionService;
        this.itemProcessor = itemProcessor;
        this.idGenerator = idGenerator;
        this.evalSuiteConfig = evalSuiteConfig;
        this.experimentExecutionConfig = experimentExecutionConfig;
        this.executorService = Executors.newFixedThreadPool(experimentExecutionConfig.getMaxConcurrentItems());
    }

    /**
     * Creates experiments and dispatches async processing.
     * Returns immediately with experiment IDs so the caller can start polling.
     * Dataset items are streamed rather than collected into memory to support large datasets.
     */
    public Mono<ExperimentExecutionResponse> createAndExecute(
            @NonNull ExperimentExecutionRequest request) {

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            String projectName = request.projectName() != null
                    ? request.projectName()
                    : experimentExecutionConfig.getDefaultProjectName();

            ExecutionPolicy datasetExecutionPolicy = fetchDatasetExecutionPolicy(
                    request.datasetId(), request.versionHash(), workspaceId);

            return createExperiments(request, projectName)
                    .collectList()
                    .flatMap(experimentEntries -> {
                        List<UUID> experimentIds = experimentEntries.stream()
                                .map(ExperimentEntry::experimentId).toList();
                        List<ExperimentExecutionResponse.ExperimentInfo> experimentInfos = experimentEntries.stream()
                                .map(ExperimentEntry::info).toList();

                        var totalItems = new AtomicInteger(0);
                        var futures = Collections.synchronizedList(new ArrayList<CompletableFuture<Void>>());

                        return streamDatasetItems(request)
                                .doOnNext(item -> dispatchItemProcessing(
                                        item, request, experimentIds, datasetExecutionPolicy,
                                        projectName, workspaceId, userName, totalItems, futures))
                                .count()
                                .map(itemCount -> {
                                    if (itemCount == 0) {
                                        log.warn("No dataset items found for dataset '{}', workspaceId '{}'",
                                                request.datasetName(), workspaceId);
                                    } else {
                                        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                                                .whenComplete((v, ex) -> {
                                                    if (ex != null) {
                                                        log.error("Unexpected error during experiment processing", ex);
                                                    }
                                                    finishExperiments(experimentIds, workspaceId, userName);
                                                });

                                        log.info(
                                                "Created '{}' experiments with '{}' total items for dataset '{}', workspaceId '{}'",
                                                experimentIds.size(), totalItems.get(), request.datasetName(),
                                                workspaceId);
                                    }

                                    return ExperimentExecutionResponse.builder()
                                            .experiments(experimentInfos)
                                            .totalItems(totalItems.get())
                                            .build();
                                });
                    });
        });
    }

    private record ExperimentEntry(UUID experimentId, ExperimentExecutionResponse.ExperimentInfo info) {
    }

    private Flux<ExperimentEntry> createExperiments(ExperimentExecutionRequest request, String projectName) {
        return Flux.range(0, request.prompts().size())
                .concatMap(i -> {
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
                                    prompt.promptVersions() != null
                                            ? prompt.promptVersions()
                                            : request.promptVersions())
                            .build();

                    return experimentService.create(experiment)
                            .map(id -> new ExperimentEntry(experimentId,
                                    ExperimentExecutionResponse.ExperimentInfo.builder()
                                            .experimentId(experimentId)
                                            .promptIndex(i)
                                            .build()));
                });
    }

    private Flux<DatasetItem> streamDatasetItems(ExperimentExecutionRequest request) {
        return Flux.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            var streamRequest = DatasetItemStreamRequest.builder()
                    .datasetName(request.datasetName())
                    .datasetVersion(request.versionHash())
                    .build();
            return datasetItemService.getItems(workspaceId, streamRequest, List.of());
        });
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

    private int getEffectiveRunsPerItem(ExecutionPolicy itemPolicy, ExecutionPolicy versionPolicy) {
        if (itemPolicy != null && itemPolicy.runsPerItem() > 0) {
            return itemPolicy.runsPerItem();
        }
        if (versionPolicy != null && versionPolicy.runsPerItem() > 0) {
            return versionPolicy.runsPerItem();
        }
        return evalSuiteConfig.getDefaultRunsPerItem();
    }

    private void dispatchItemProcessing(
            DatasetItem item,
            ExperimentExecutionRequest request,
            List<UUID> experimentIds,
            ExecutionPolicy datasetExecutionPolicy,
            String projectName,
            String workspaceId,
            String userName,
            AtomicInteger totalItems,
            List<CompletableFuture<Void>> futures) {

        int runsPerItem = getEffectiveRunsPerItem(item.executionPolicy(), datasetExecutionPolicy);
        totalItems.addAndGet(runsPerItem * request.prompts().size());

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

    private void finishExperiments(List<UUID> experimentIds, String workspaceId, String userName) {
        try {
            var reactorContext = reactor.util.context.Context.of(
                    RequestContext.WORKSPACE_ID, workspaceId,
                    RequestContext.USER_NAME, userName,
                    RequestContext.WORKSPACE_NAME, workspaceId,
                    RequestContext.VISIBILITY, com.comet.opik.api.Visibility.PRIVATE);

            var statusUpdate = com.comet.opik.api.ExperimentUpdate.builder()
                    .status(ExperimentStatus.COMPLETED)
                    .build();

            Flux.fromIterable(experimentIds)
                    .concatMap(experimentId -> experimentService.update(experimentId, statusUpdate))
                    .then(experimentService.finishExperiments(Set.copyOf(experimentIds)))
                    .contextWrite(reactorContext)
                    .block();

            log.info("Finished '{}' experiments", experimentIds.size());
        } catch (Exception e) {
            log.error("Failed to finish experiments", e);
        }
    }
}
