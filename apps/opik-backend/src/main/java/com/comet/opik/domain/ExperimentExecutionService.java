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
import com.comet.opik.api.OpikPromptEntry;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.TemplateStructure;
import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.api.resources.v1.events.TestSuiteEvaluatorMapper;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
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
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Singleton
@Slf4j
public class ExperimentExecutionService {

    private final ExperimentService experimentService;
    private final DatasetItemService datasetItemService;
    private final DatasetVersionService datasetVersionService;
    private final ExperimentItemPublisher itemPublisher;
    private final IdGenerator idGenerator;
    private final TestSuiteEvaluatorMapper testSuiteEvaluatorMapper;
    private final ExperimentExecutionConfig experimentExecutionConfig;
    private final PromptService promptService;

    @Inject
    public ExperimentExecutionService(
            @NonNull ExperimentService experimentService,
            @NonNull DatasetItemService datasetItemService,
            @NonNull DatasetVersionService datasetVersionService,
            @NonNull ExperimentItemPublisher itemPublisher,
            @NonNull IdGenerator idGenerator,
            @NonNull TestSuiteEvaluatorMapper testSuiteEvaluatorMapper,
            @NonNull @Config("experimentExecution") ExperimentExecutionConfig experimentExecutionConfig,
            @NonNull PromptService promptService) {
        this.experimentService = experimentService;
        this.datasetItemService = datasetItemService;
        this.datasetVersionService = datasetVersionService;
        this.itemPublisher = itemPublisher;
        this.idGenerator = idGenerator;
        this.testSuiteEvaluatorMapper = testSuiteEvaluatorMapper;
        this.experimentExecutionConfig = experimentExecutionConfig;
        this.promptService = promptService;
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
                                .flatMap(experimentEntries -> resolveOpikPromptsByVariant(request)
                                        .flatMap(opikPromptsByVariant -> {
                                            List<UUID> experimentIds = experimentEntries.stream()
                                                    .map(ExperimentEntry::experimentId).toList();
                                            List<ExperimentExecutionResponse.ExperimentInfo> experimentInfos = experimentEntries
                                                    .stream()
                                                    .map(ExperimentEntry::info).toList();

                                            UUID batchId = idGenerator.generateId();

                                            return streamDatasetItems(request)
                                                    .flatMapIterable(item -> buildMessages(
                                                            item, request, experimentIds, datasetExecutionPolicy,
                                                            projectName, workspaceId, userName, batchId,
                                                            opikPromptsByVariant))
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
                                        }));
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
                            .evaluationMethod(EvaluationMethod.TEST_SUITE)
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
        return testSuiteEvaluatorMapper.getEffectiveRunsPerItem(itemPolicy, versionPolicy);
    }

    private List<ExperimentItemToProcess> buildMessages(
            DatasetItem item,
            ExperimentExecutionRequest request,
            List<UUID> experimentIds,
            ExecutionPolicy datasetExecutionPolicy,
            String projectName,
            String workspaceId,
            String userName,
            UUID batchId,
            List<List<OpikPromptEntry>> opikPromptsByVariant) {

        int runsPerItem = getEffectiveRunsPerItem(item.executionPolicy(), datasetExecutionPolicy);
        var messages = new ArrayList<ExperimentItemToProcess>();

        for (int run = 0; run < runsPerItem; run++) {
            for (int promptIdx = 0; promptIdx < request.prompts().size(); promptIdx++) {
                var prompt = request.prompts().get(promptIdx);
                UUID experimentId = experimentIds.get(promptIdx);
                List<OpikPromptEntry> opikPrompts = opikPromptsByVariant.get(promptIdx);

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
                        .opikPrompts(opikPrompts)
                        .build());
            }
        }

        return messages;
    }

    /**
     * Resolves the prompt versions linked to each variant via a single bulk lookup against
     * the prompt store, and returns one prebuilt {@code opik_prompts} list per variant (in
     * the order of {@code request.prompts()}). This avoids re-doing the lookup for every
     * dataset item — large experiments would otherwise hit the prompt store thousands of
     * times for the same set of version ids.
     */
    private Mono<List<List<OpikPromptEntry>>> resolveOpikPromptsByVariant(ExperimentExecutionRequest request) {
        List<List<Experiment.PromptVersionLink>> linksByVariant = request.prompts().stream()
                .map(variant -> variant.promptVersions() != null
                        ? variant.promptVersions()
                        : request.promptVersions())
                .map(links -> links == null ? List.<Experiment.PromptVersionLink>of() : links)
                .toList();

        Set<UUID> uniqueVersionIds = linksByVariant.stream()
                .flatMap(List::stream)
                .map(Experiment.PromptVersionLink::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (uniqueVersionIds.isEmpty()) {
            return Mono.just(linksByVariant.stream()
                    .map(unused -> List.<OpikPromptEntry>of())
                    .toList());
        }

        return promptService.findVersionByIds(uniqueVersionIds)
                .map(versionsById -> linksByVariant.stream()
                        .map(links -> buildOpikPromptEntries(links, versionsById))
                        .toList())
                .onErrorResume(error -> {
                    log.warn(
                            "Failed to resolve prompt versions for opik_prompts metadata; traces will not be linked to their prompt(s) for dataset '{}' (id '{}', versionHash '{}')",
                            request.datasetName(), request.datasetId(), request.versionHash(), error);
                    return Mono.just(linksByVariant.stream()
                            .map(unused -> List.<OpikPromptEntry>of())
                            .toList());
                });
    }

    private static List<OpikPromptEntry> buildOpikPromptEntries(List<Experiment.PromptVersionLink> links,
            Map<UUID, PromptVersion> versionsById) {
        if (links == null || links.isEmpty()) {
            return List.of();
        }
        return links.stream()
                .map(link -> toOpikPromptEntry(link, versionsById.get(link.id())))
                .filter(Objects::nonNull)
                .toList();
    }

    private static OpikPromptEntry toOpikPromptEntry(Experiment.PromptVersionLink link, PromptVersion version) {
        if (version == null) {
            return null;
        }
        UUID promptId = version.promptId() != null ? version.promptId() : link.promptId();
        return OpikPromptEntry.builder()
                .id(promptId)
                .name(link.promptName())
                .templateStructure(version.templateStructure())
                .version(OpikPromptEntry.Version.builder()
                        .id(version.id())
                        .template(toTemplateNode(version.template(), version.templateStructure()))
                        .commit(version.commit())
                        .versionNumber(version.versionNumber())
                        .metadata(version.metadata())
                        .build())
                .build();
    }

    private static JsonNode toTemplateNode(String template, TemplateStructure structure) {
        if (template == null) {
            return null;
        }
        if (structure == TemplateStructure.CHAT) {
            return JsonUtils.getJsonNodeFromStringWithFallback(template);
        }
        return TextNode.valueOf(template);
    }
}
