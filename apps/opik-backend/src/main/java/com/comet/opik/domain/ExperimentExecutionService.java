package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.EvaluationMethod;
import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.ExperimentExecutionResponse;
import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.api.resources.v1.events.EvalSuiteEvaluatorMapper;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

@Singleton
@Slf4j
public class ExperimentExecutionService {

    private final ExperimentService experimentService;
    private final DatasetItemService datasetItemService;
    private final DatasetVersionService datasetVersionService;
    private final ExperimentItemPublisher itemPublisher;
    private final IdGenerator idGenerator;
    private final EvalSuiteEvaluatorMapper evalSuiteEvaluatorMapper;
    private final ExperimentExecutionConfig experimentExecutionConfig;

    @Inject
    public ExperimentExecutionService(
            @NonNull ExperimentService experimentService,
            @NonNull DatasetItemService datasetItemService,
            @NonNull DatasetVersionService datasetVersionService,
            @NonNull ExperimentItemPublisher itemPublisher,
            @NonNull IdGenerator idGenerator,
            @NonNull EvalSuiteEvaluatorMapper evalSuiteEvaluatorMapper,
            @NonNull @Config("experimentExecution") ExperimentExecutionConfig experimentExecutionConfig) {
        this.experimentService = experimentService;
        this.datasetItemService = datasetItemService;
        this.datasetVersionService = datasetVersionService;
        this.itemPublisher = itemPublisher;
        this.idGenerator = idGenerator;
        this.evalSuiteEvaluatorMapper = evalSuiteEvaluatorMapper;
        this.experimentExecutionConfig = experimentExecutionConfig;
    }

    /**
     * Creates experiments and publishes item processing messages to Redis Streams.
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

            return fetchDatasetExecutionPolicyReactive(request.datasetId(), request.versionHash())
                    .flatMap(optPolicy -> {
                        ExecutionPolicy datasetExecutionPolicy = optPolicy.orElse(null);
                        return createExperiments(request, projectName)
                                .collectSortedList(Comparator.comparingInt(e -> e.info().promptIndex()))
                                .flatMap(experimentEntries -> {
                                    List<UUID> experimentIds = experimentEntries.stream()
                                            .map(ExperimentEntry::experimentId).toList();
                                    List<ExperimentExecutionResponse.ExperimentInfo> experimentInfos = experimentEntries
                                            .stream()
                                            .map(ExperimentEntry::info).toList();

                                    UUID batchId = idGenerator.generateId();

                                    return streamDatasetItems(request)
                                            .flatMapIterable(item -> buildMessages(
                                                    item, request, experimentIds, datasetExecutionPolicy,
                                                    projectName, workspaceId, userName, batchId))
                                            .collectList()
                                            .flatMap(messages -> {
                                                if (messages.isEmpty()) {
                                                    log.warn(
                                                            "No dataset items found for dataset '{}', workspaceId '{}'",
                                                            request.datasetName(), workspaceId);
                                                    return markExperimentsCompleted(experimentIds)
                                                            .thenReturn(ExperimentExecutionResponse.builder()
                                                                    .experiments(experimentInfos)
                                                                    .totalItems(0)
                                                                    .build());
                                                }

                                                return itemPublisher.publish(batchId, messages)
                                                        .then(Mono.fromCallable(() -> {
                                                            log.info(
                                                                    "Created '{}' experiments with '{}' total items for dataset '{}', workspaceId '{}'",
                                                                    experimentIds.size(), messages.size(),
                                                                    request.datasetName(),
                                                                    workspaceId);

                                                            return ExperimentExecutionResponse.builder()
                                                                    .experiments(experimentInfos)
                                                                    .totalItems(messages.size())
                                                                    .build();
                                                        }));
                                            });
                                });
                    });
        });
    }

    private record ExperimentEntry(UUID experimentId, ExperimentExecutionResponse.ExperimentInfo info) {
    }

    private Flux<ExperimentEntry> createExperiments(ExperimentExecutionRequest request, String projectName) {
        var monos = IntStream.range(0, request.prompts().size())
                .mapToObj(i -> {
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
                })
                .toList();
        return Flux.merge(monos);
    }

    private Flux<DatasetItem> streamDatasetItems(ExperimentExecutionRequest request) {
        var streamRequest = DatasetItemStreamRequest.builder()
                .datasetName(request.datasetName())
                .datasetVersion(request.versionHash())
                .build();
        return datasetItemService.getItems(streamRequest, List.of());
    }

    private Mono<Optional<ExecutionPolicy>> fetchDatasetExecutionPolicyReactive(UUID datasetId, String versionHash) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            return Mono.fromCallable(
                    () -> Optional.ofNullable(fetchDatasetExecutionPolicy(datasetId, versionHash, workspaceId)))
                    .subscribeOn(Schedulers.boundedElastic());
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
        } catch (NotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to fetch dataset execution policy for dataset '{}', versionHash '{}'",
                    datasetId, versionHash, e);
            return null;
        }
    }

    private Mono<Void> markExperimentsCompleted(List<UUID> experimentIds) {
        var statusUpdate = ExperimentUpdate.builder()
                .status(ExperimentStatus.COMPLETED)
                .build();
        return Flux.fromIterable(experimentIds)
                .concatMap(id -> experimentService.update(id, statusUpdate))
                .then(experimentService.finishExperiments(Set.copyOf(experimentIds)))
                .then();
    }

    private int getEffectiveRunsPerItem(ExecutionPolicy itemPolicy, ExecutionPolicy versionPolicy) {
        return evalSuiteEvaluatorMapper.getEffectiveRunsPerItem(itemPolicy, versionPolicy);
    }

    private List<ExperimentItemToProcess> buildMessages(
            DatasetItem item,
            ExperimentExecutionRequest request,
            List<UUID> experimentIds,
            ExecutionPolicy datasetExecutionPolicy,
            String projectName,
            String workspaceId,
            String userName,
            UUID batchId) {

        int runsPerItem = getEffectiveRunsPerItem(item.executionPolicy(), datasetExecutionPolicy);
        var messages = new ArrayList<ExperimentItemToProcess>();

        for (int run = 0; run < runsPerItem; run++) {
            for (int promptIdx = 0; promptIdx < request.prompts().size(); promptIdx++) {
                var prompt = request.prompts().get(promptIdx);
                UUID experimentId = experimentIds.get(promptIdx);

                messages.add(ExperimentItemToProcess.builder()
                        .batchId(batchId)
                        .prompt(prompt)
                        .datasetItemId(item.id())
                        .experimentId(experimentId)
                        .datasetId(request.datasetId())
                        .versionHash(request.versionHash())
                        .projectName(projectName)
                        .workspaceId(workspaceId)
                        .userName(userName)
                        .allExperimentIds(experimentIds)
                        .build());
            }
        }

        return messages;
    }
}
