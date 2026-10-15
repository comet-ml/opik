package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.DatasetType;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.EvaluationMethod;
import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.ExperimentExecutionResponse;
import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.TemplateStructure;
import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.api.filter.DatasetItemField;
import com.comet.opik.api.filter.DatasetItemFilter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.resources.v1.events.TestSuiteEvaluatorMapper;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.TestSuiteConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExperimentExecutionService Test")
class ExperimentExecutionServiceTest {

    private static final String WORKSPACE_ID = "test-workspace-id";
    private static final String USER_NAME = "test-user";

    @Mock
    private ExperimentService experimentService;

    @Mock
    private ExperimentCancellationService cancellationService;

    @Mock
    private DatasetService datasetService;

    @Mock
    private DatasetItemService datasetItemService;

    @Mock
    private DatasetVersionService datasetVersionService;

    @Mock
    private FeatureFlags featureFlags;

    @Mock
    private ExperimentItemPublisher itemPublisher;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private PromptService promptService;

    private ExperimentExecutionService service;

    @BeforeEach
    void setUp() {
        var testSuiteConfig = new TestSuiteConfig();
        var evaluatorMapper = new TestSuiteEvaluatorMapper(testSuiteConfig);
        service = new ExperimentExecutionService(
                experimentService, cancellationService, datasetService, datasetItemService,
                datasetVersionService,
                itemPublisher, idGenerator, evaluatorMapper, new ExperimentExecutionConfig(), promptService,
                featureFlags);

        lenient().when(featureFlags.isDatasetVersioningEnabled()).thenReturn(true);
        lenient().when(itemPublisher.publish(any(), any(), anyBoolean())).thenReturn(Mono.empty());
        // An empty queue, so only the tests that fill it see the run refused.
        lenient().when(itemPublisher.queuedMessageCount()).thenReturn(Mono.just(0L));
        // Every publish asks whether a stop landed while it was running; almost no test is about that.
        lenient().when(cancellationService.isCancelled(any(), any(UUID.class))).thenReturn(Mono.just(false));
        stubDatasetType(DatasetType.TEST_SUITE);
        // Default empty stub so tests that don't care about the version-info bulk lookup
        // (used by resolveOpikPromptsByVariant for prompt-name fallback) don't NPE the
        // Mono.zip on an un-stubbed call.
        lenient().when(promptService.getVersionsInfoByVersionsIds(any()))
                .thenReturn(Mono.just(Map.of()));
    }

    private ExperimentExecutionRequest.PromptVariant buildPrompt(String model, String content) {
        return ExperimentExecutionRequest.PromptVariant.builder()
                .model(model)
                .messages(List.of(
                        ExperimentExecutionRequest.PromptVariant.Message.builder()
                                .role("user")
                                .content(new TextNode(content))
                                .build()))
                .build();
    }

    private ExperimentExecutionResponse executeRequest(ExperimentExecutionRequest request) {
        return executeRequest(request, List.of());
    }

    private ExperimentExecutionResponse executeRequest(ExperimentExecutionRequest request,
            List<DatasetItemFilter> filters) {
        return service.createAndExecute(request, filters)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                        .put(RequestContext.USER_NAME, USER_NAME)
                        .put(RequestContext.VISIBILITY, com.comet.opik.api.Visibility.PRIVATE))
                .block();
    }

    private DatasetItem buildDatasetItem(UUID id, ExecutionPolicy executionPolicy) {
        return DatasetItem.builder()
                .id(id)
                .data(Map.of("input", new TextNode("hello")))
                .executionPolicy(executionPolicy)
                .build();
    }

    private void stubDatasetType(DatasetType type) {
        lenient().when(datasetService.findById(any(UUID.class), any(), any()))
                .thenReturn(Dataset.builder().id(UUID.randomUUID()).name("test-dataset").type(type).build());
    }

    private void stubDatasetItems(List<DatasetItem> items) {
        when(datasetItemService.getItems(any(DatasetItemStreamRequest.class), any()))
                .thenReturn(Flux.fromIterable(items));
    }

    private void stubExperimentCreate() {
        when(experimentService.create(any(Experiment.class)))
                .thenReturn(Mono.just(UUID.randomUUID()));
    }

    private void stubFinishExperiments() {
        lenient().when(experimentService.finishExperiments(any()))
                .thenReturn(Mono.empty());
    }

    @Nested
    @DisplayName("Empty dataset")
    class EmptyDataset {

        @Test
        void createAndExecuteWhenNoDatasetItemsReturnsZeroTotalItems() {
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildPrompt("gpt-4", "Hello {{input}}")))
                    .build();

            stubDatasetItems(List.of());
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();
            lenient().when(experimentService.update(any(UUID.class), any()))
                    .thenReturn(Mono.empty());

            var response = executeRequest(request);

            assertThat(response.totalItems()).isZero();
        }
    }

    @Nested
    @DisplayName("Experiment creation")
    class ExperimentCreation {

        @Test
        void createAndExecuteCreatesOneExperimentPerPromptVariant() {
            var prompt1 = buildPrompt("gpt-4", "Hello {{input}}");
            var prompt2 = buildPrompt("claude-3", "Hi {{input}}");
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(prompt1, prompt2))
                    .build();

            var itemId = UUID.randomUUID();
            stubDatasetItems(List.of(buildDatasetItem(itemId, null)));

            var expId1 = UUID.randomUUID();
            var expId2 = UUID.randomUUID();
            when(idGenerator.generateId()).thenReturn(expId1, expId2);
            stubExperimentCreate();
            stubFinishExperiments();

            var response = executeRequest(request);

            assertThat(response.experiments()).hasSize(2);
            assertThat(response.experiments().getFirst().promptIndex()).isZero();
            assertThat(response.experiments().get(1).promptIndex()).isEqualTo(1);
            assertThat(response.experiments().getFirst().experimentId()).isEqualTo(expId1);
            assertThat(response.experiments().get(1).experimentId()).isEqualTo(expId2);
        }

        @Test
        void createAndExecuteSetsTestSuiteMethodAndRunningStatus() {
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(request);

            var captor = ArgumentCaptor.forClass(Experiment.class);
            verify(experimentService).create(captor.capture());

            var experiment = captor.getValue();
            assertThat(experiment.evaluationMethod()).isEqualTo(EvaluationMethod.TEST_SUITE);
            assertThat(experiment.status()).isEqualTo(ExperimentStatus.RUNNING);
        }

        @Test
        void createAndExecuteSetsDatasetMethodForARegularDataset() {
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build();

            stubDatasetType(DatasetType.DATASET);
            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(request);

            var captor = ArgumentCaptor.forClass(Experiment.class);
            verify(experimentService).create(captor.capture());

            assertThat(captor.getValue().evaluationMethod()).isEqualTo(EvaluationMethod.DATASET);
        }

        @Test
        void createAndExecuteUsesCustomProjectNameWhenProvided() {
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .projectName("my-project")
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(request);

            var captor = ArgumentCaptor.forClass(Experiment.class);
            verify(experimentService).create(captor.capture());
            assertThat(captor.getValue().projectName()).isEqualTo("my-project");
        }

        @Test
        void createAndExecuteCarriesTheProjectIdWhenTheCallerKnowsIt() {
            var projectId = UUID.randomUUID();
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .projectName("my-project")
                    .projectId(projectId)
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(request);

            var captor = ArgumentCaptor.forClass(Experiment.class);
            verify(experimentService).create(captor.capture());
            assertThat(captor.getValue().projectId()).isEqualTo(projectId);
            assertThat(captor.getValue().projectName())
                    .as("still carried, since it is what the run's traces are written under")
                    .isEqualTo("my-project");
        }

        @Test
        void createAndExecuteUsesDefaultProjectNameWhenNotProvided() {
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(request);

            var captor = ArgumentCaptor.forClass(Experiment.class);
            verify(experimentService).create(captor.capture());
            assertThat(captor.getValue().projectName()).isEqualTo("playground");
        }
    }

    @Nested
    @DisplayName("Experiment naming")
    class ExperimentNaming {

        @Test
        void createAndExecuteLeavesNameNullWhenNotProvided() {
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(request);

            var captor = ArgumentCaptor.forClass(Experiment.class);
            verify(experimentService).create(captor.capture());
            assertThat(captor.getValue().name()).isNull();
        }

        @Test
        void createAndExecuteLeavesNameBlankWhenOnlyWhitespaceProvided() {
            var prompt = buildPrompt("gpt-4", "Hello").toBuilder()
                    .experimentName("   ")
                    .build();
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(prompt))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(request);

            var captor = ArgumentCaptor.forClass(Experiment.class);
            verify(experimentService).create(captor.capture());
            assertThat(captor.getValue().name()).isBlank();
        }

        @Test
        void createAndExecuteUsesExperimentNameProvidedOnPromptVariant() {
            var prompt = buildPrompt("gpt-4", "Hello").toBuilder()
                    .experimentName("my-experiment")
                    .build();
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(prompt))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(request);

            var captor = ArgumentCaptor.forClass(Experiment.class);
            verify(experimentService).create(captor.capture());
            assertThat(captor.getValue().name()).isEqualTo("my-experiment");
        }

        @Test
        void createAndExecuteUsesEachPromptVariantOwnExperimentName() {
            var prompt1 = buildPrompt("gpt-4", "Hello {{input}}").toBuilder()
                    .experimentName("first-experiment")
                    .build();
            var prompt2 = buildPrompt("claude-3", "Hi {{input}}").toBuilder()
                    .experimentName("second-experiment")
                    .build();
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(prompt1, prompt2))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID(), UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(request);

            var captor = ArgumentCaptor.forClass(Experiment.class);
            verify(experimentService, times(2)).create(captor.capture());
            var names = captor.getAllValues().stream().map(Experiment::name).toList();
            assertThat(names).containsExactly("first-experiment", "second-experiment");
        }
    }

    @Nested
    @DisplayName("Execution policy and total items calculation")
    class ExecutionPolicyAndTotalItems {

        @Test
        void createAndExecuteCalculatesTotalItemsWithDefaultRunsPerItem() {
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build();

            stubDatasetItems(List.of(
                    buildDatasetItem(UUID.randomUUID(), null),
                    buildDatasetItem(UUID.randomUUID(), null),
                    buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            var response = executeRequest(request);

            assertThat(response.totalItems()).isEqualTo(3);
        }

        // The policy decides how many times each item runs and what counts as a pass. Defaulting on a
        // read that failed runs a suite configured for three runs once, and reports its pass rate
        // against thresholds nobody chose — with nothing in the response to say so.
        @Test
        void createAndExecuteFailsRatherThanRunWithoutThePolicyItCouldNotRead() {
            var datasetId = UUID.randomUUID();
            when(datasetVersionService.getLatestVersion(any(UUID.class), any()))
                    .thenThrow(new IllegalStateException("version store is down"));

            assertThatThrownBy(() -> executeRequest(ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(datasetId)
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("version store is down");

            verify(experimentService, never()).create(any(Experiment.class));
        }

        @Test
        void createAndExecuteUsesVersionLevelExecutionPolicy() {
            var datasetId = UUID.randomUUID();
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(datasetId)
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build();

            stubDatasetItems(List.of(
                    buildDatasetItem(UUID.randomUUID(), null),
                    buildDatasetItem(UUID.randomUUID(), null)));

            var versionPolicy = new ExecutionPolicy(3, 1);
            var version = DatasetVersion.builder()
                    .executionPolicy(versionPolicy)
                    .build();
            when(datasetVersionService.getLatestVersion(datasetId, WORKSPACE_ID))
                    .thenReturn(Optional.of(version));

            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            var response = executeRequest(request);

            assertThat(response.totalItems()).isEqualTo(6);
        }

        @Test
        void createAndExecuteItemLevelPolicyOverridesVersionLevel() {
            var datasetId = UUID.randomUUID();
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(datasetId)
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build();

            var itemPolicy = new ExecutionPolicy(5, 1);
            stubDatasetItems(List.of(
                    buildDatasetItem(UUID.randomUUID(), itemPolicy),
                    buildDatasetItem(UUID.randomUUID(), null)));

            var versionPolicy = new ExecutionPolicy(3, 1);
            var version = DatasetVersion.builder()
                    .executionPolicy(versionPolicy)
                    .build();
            when(datasetVersionService.getLatestVersion(datasetId, WORKSPACE_ID))
                    .thenReturn(Optional.of(version));

            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            var response = executeRequest(request);

            assertThat(response.totalItems()).isEqualTo(8);
        }

        // The batch total describes the run, not a variant. A caller can re-run one variant while its
        // siblings are still going, so each needs the number its own item counter was seeded with
        // rather than a share divided out of the whole.
        @Test
        void createAndExecuteReportsWhatEachVariantDispatched() {
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(
                            buildPrompt("gpt-4", "first"),
                            buildPrompt("gpt-4", "second")))
                    .build();

            stubDatasetItems(List.of(
                    buildDatasetItem(UUID.randomUUID(), null),
                    buildDatasetItem(UUID.randomUUID(), null),
                    buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenAnswer(invocation -> UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            var response = executeRequest(request);

            assertThat(response.totalItems())
                    .as("three items across two variants")
                    .isEqualTo(6);
            assertThat(response.experiments())
                    .as("each variant dispatched the three items, not the batch's six")
                    .allSatisfy(info -> assertThat(info.totalItems()).isEqualTo(3));
        }

        @Test
        void createAndExecuteMultipliesItemsByPromptCount() {
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(
                            buildPrompt("gpt-4", "Hello"),
                            buildPrompt("claude-3", "Hi")))
                    .build();

            stubDatasetItems(List.of(
                    buildDatasetItem(UUID.randomUUID(), null),
                    buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID(), UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            var response = executeRequest(request);

            assertThat(response.totalItems()).isEqualTo(4);
        }

        @Test
        void createAndExecuteFetchesVersionByHashWhenProvided() {
            var datasetId = UUID.randomUUID();
            var versionHash = "abc123";
            var versionId = UUID.randomUUID();
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(datasetId)
                    .versionHash(versionHash)
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));

            var versionPolicy = new ExecutionPolicy(2, 1);
            var version = DatasetVersion.builder()
                    .executionPolicy(versionPolicy)
                    .build();
            when(datasetVersionService.resolveVersionId(WORKSPACE_ID, datasetId, versionHash))
                    .thenReturn(versionId);
            when(datasetVersionService.getVersionById(WORKSPACE_ID, datasetId, versionId))
                    .thenReturn(version);

            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            var response = executeRequest(request);

            assertThat(response.totalItems()).isEqualTo(2);
            verify(datasetVersionService).resolveVersionId(WORKSPACE_ID, datasetId, versionHash);
            verify(datasetVersionService).getVersionById(WORKSPACE_ID, datasetId, versionId);
            verify(datasetVersionService, never()).getLatestVersion(any(), any());
        }

    }

    @Nested
    @DisplayName("Experiment metadata")
    class ExperimentMetadata {

        @Test
        void createAndExecuteIncludesModelAndMessagesInMetadata() {
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildPrompt("gpt-4o", "Tell me about {{input}}")))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(request);

            var captor = ArgumentCaptor.forClass(Experiment.class);
            verify(experimentService).create(captor.capture());

            var metadata = captor.getValue().metadata();
            assertThat(metadata).isNotNull();
            assertThat(metadata.get("model").asText()).isEqualTo("gpt-4o");
            assertThat(metadata.has("messages")).isTrue();
        }

        @Test
        void createAndExecuteIncludesModelConfigInMetadata() {
            var configs = Map.<String, JsonNode>of(
                    "temperature", new TextNode("0.7"),
                    "maxCompletionTokens", new TextNode("100"));
            var prompt = ExperimentExecutionRequest.PromptVariant.builder()
                    .model("gpt-4")
                    .messages(List.of(
                            ExperimentExecutionRequest.PromptVariant.Message.builder()
                                    .role("user")
                                    .content(new TextNode("Hello"))
                                    .build()))
                    .configs(configs)
                    .build();

            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(prompt))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(request);

            var captor = ArgumentCaptor.forClass(Experiment.class);
            verify(experimentService).create(captor.capture());

            var metadata = captor.getValue().metadata();
            assertThat(metadata.has("model_config")).isTrue();
        }
    }

    @Nested
    @DisplayName("Queue bound")
    class QueueBound {

        // The queue trims by length without sparing entries no consumer has taken, so a run bigger
        // than it deletes its own items: they never execute, the run never drains, and nothing says
        // why. Refusing it up front is the difference between a clear error and silent loss.
        @Test
        void refuseARunThatWouldNotFitTheQueue() {
            var config = new ExperimentExecutionConfig();
            config.setStreamMaxLen(1000);
            service = new ExperimentExecutionService(
                    experimentService, cancellationService, datasetService, datasetItemService,
                    datasetVersionService, itemPublisher, idGenerator,
                    new TestSuiteEvaluatorMapper(new TestSuiteConfig()), config, promptService, featureFlags);

            stubDatasetItems(IntStream.range(0, 501)
                    .mapToObj(i -> buildDatasetItem(UUID.randomUUID(), null))
                    .toList());
            when(idGenerator.generateId()).thenAnswer(invocation -> UUID.randomUUID());

            // Two variants over 501 items is 1,002 messages against a queue bounded at 1,000. The run
            // is refused on the first message past the bound, so the message names the bound, not a
            // total nobody counted.
            assertThatThrownBy(() -> executeRequest(ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildPrompt("gpt-4", "Hello"), buildPrompt("gpt-4", "Hi")))
                    .build()))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("more than")
                    .hasMessageContaining("1,000");

            verify(itemPublisher, never()).publish(any(), any(), anyBoolean());

            // Nothing written at all. The variants are planned with ids of our own and only persisted
            // once the run is one we will accept, so a refusal leaves no experiments behind rather
            // than a workspace of FAILED records for a run that never started.
            verify(experimentService, never()).create(any(Experiment.class));
            verify(experimentService, never()).update(any(UUID.class), any());
        }

        @Test
        void publishARunThatFits() {
            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build());

            verify(itemPublisher).publish(any(), any(), anyBoolean());
        }

        // The bound belongs to the stream, which every workspace shares. A run small enough on its
        // own still trims entries off the front when others are already queued, and those belong to
        // somebody else's run, which then never drains.
        @Test
        void refuseARunThatWouldNotFitBesideTheBacklog() {
            var config = new ExperimentExecutionConfig();
            config.setStreamMaxLen(1000);
            service = new ExperimentExecutionService(
                    experimentService, cancellationService, datasetService, datasetItemService,
                    datasetVersionService, itemPublisher, idGenerator,
                    new TestSuiteEvaluatorMapper(new TestSuiteConfig()), config, promptService, featureFlags);

            stubDatasetItems(IntStream.range(0, 400)
                    .mapToObj(i -> buildDatasetItem(UUID.randomUUID(), null))
                    .toList());
            when(idGenerator.generateId()).thenAnswer(invocation -> UUID.randomUUID());
            when(itemPublisher.queuedMessageCount()).thenReturn(Mono.just(700L));

            // 400 items would fit on their own. Behind 700 already queued they do not.
            assertThatThrownBy(() -> executeRequest(ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build()))
                    .isInstanceOf(InternalServerErrorException.class)
                    .hasMessageContaining("too busy");

            // Nothing written and nothing queued. publish() is not asserted on: the chain is built
            // before the guard decides, so the call is recorded whether or not it is ever subscribed.
            verify(experimentService, never()).create(any(Experiment.class));
            verify(experimentService, never()).update(any(UUID.class), any());
            verify(cancellationService, never()).recordQueued(any(), any());
        }

        @Test
        void publishARunThatFitsBesideTheBacklog() {
            var config = new ExperimentExecutionConfig();
            config.setStreamMaxLen(1000);
            service = new ExperimentExecutionService(
                    experimentService, cancellationService, datasetService, datasetItemService,
                    datasetVersionService, itemPublisher, idGenerator,
                    new TestSuiteEvaluatorMapper(new TestSuiteConfig()), config, promptService, featureFlags);

            stubDatasetItems(IntStream.range(0, 300)
                    .mapToObj(i -> buildDatasetItem(UUID.randomUUID(), null))
                    .toList());
            when(idGenerator.generateId()).thenAnswer(invocation -> UUID.randomUUID());
            when(itemPublisher.queuedMessageCount()).thenReturn(Mono.just(700L));
            stubExperimentCreate();
            stubFinishExperiments();

            // Exactly the bound, which is still room.
            executeRequest(ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build());

            verify(itemPublisher).publish(any(), any(), anyBoolean());
        }
    }

    @Nested
    @DisplayName("Cancellation")
    class Cancellation {

        // Cancelling reads each experiment first, to leave the ones that already finished alone.
        @BeforeEach
        void stubStillRunning() {
            lenient().when(experimentService.getMetadataById(any(UUID.class)))
                    .thenAnswer(invocation -> Mono.just(Experiment.builder()
                            .id(invocation.getArgument(0))
                            .datasetName("dataset")
                            .status(ExperimentStatus.RUNNING)
                            .build()));
            // The finish goes to one caller; these tests are always it, and nothing has taken it yet.
            lenient().when(cancellationService.claimFinish(any(), any(UUID.class))).thenReturn(Mono.just(true));
            // Runs this service queued; the one test about SDK-logged experiments overrides it.
            lenient().when(itemPublisher.isTracked(any(UUID.class))).thenReturn(Mono.just(true));

        }

        @Test
        void cancelMarksTheExperimentsAndStopsThem() {
            var experimentIds = Set.of(UUID.randomUUID(), UUID.randomUUID());

            when(cancellationService.cancel(any(), any())).thenReturn(Mono.empty());
            when(cancellationService.purgeQueued(any(), any(UUID.class))).thenReturn(Mono.just(false));
            when(experimentService.update(any(UUID.class), any())).thenReturn(Mono.empty());

            service.cancel(experimentIds)
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, USER_NAME)
                            .put(RequestContext.VISIBILITY, com.comet.opik.api.Visibility.PRIVATE))
                    .block();

            verify(cancellationService).cancel(eq(WORKSPACE_ID), eq(experimentIds));

            // Marking alone leaves the consumer to walk what is queued, which is what makes the next
            // run wait behind a cancelled one
            experimentIds.forEach(
                    experimentId -> verify(cancellationService).purgeQueued(WORKSPACE_ID, experimentId));

            // Status is set now rather than when the stream drains, so the caller sees the run stop
            // instead of watching it wind down.
            var captor = ArgumentCaptor.forClass(ExperimentUpdate.class);
            verify(experimentService, times(2)).update(any(UUID.class), captor.capture());
            assertThat(captor.getAllValues())
                    .allSatisfy(update -> assertThat(update.status()).isEqualTo(ExperimentStatus.CANCELLED));
        }

        // They are all marked cancelled in Redis before this point, so a sibling left un-updated reads
        // as running while its items are skipped, and its queued messages sit ahead of the next run.
        @Test
        void cancelCarriesOnAfterOneExperimentFailsToUpdate() {
            var failing = UUID.randomUUID();
            var sibling = UUID.randomUUID();

            when(cancellationService.cancel(any(), any())).thenReturn(Mono.empty());
            when(cancellationService.purgeQueued(any(), any(UUID.class))).thenReturn(Mono.just(false));
            when(experimentService.update(eq(failing), any()))
                    .thenReturn(Mono.error(new IllegalStateException("experiment is gone")));
            when(experimentService.update(eq(sibling), any())).thenReturn(Mono.empty());

            assertThatThrownBy(() -> service.cancel(Set.of(failing, sibling))
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, USER_NAME)
                            .put(RequestContext.VISIBILITY, com.comet.opik.api.Visibility.PRIVATE))
                    .block())
                    .as("reported, not swallowed: its items are skipped while its status still reads running")
                    .isInstanceOf(InternalServerErrorException.class)
                    .hasMessageContaining("1 of 2");

            verify(experimentService).update(eq(sibling), any());
            verify(cancellationService).purgeQueued(WORKSPACE_ID, sibling);
        }

        // Reproduced: an experiment created through POST /experiments, then cancelled, became
        // CANCELLED with finished_at null for ever — nothing of ours counts it down, so no consumer
        // ever stamps it, and the page waits on it indefinitely.
        @Test
        void cancelLeavesAloneAnExperimentThisServiceNeverQueued() {
            var sdkLogged = UUID.randomUUID();

            when(itemPublisher.isTracked(sdkLogged)).thenReturn(Mono.just(false));
            lenient().when(cancellationService.cancel(any(), any())).thenReturn(Mono.empty());
            lenient().when(experimentService.update(any(UUID.class), any())).thenReturn(Mono.empty());

            service.cancel(Set.of(sdkLogged))
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, USER_NAME)
                            .put(RequestContext.VISIBILITY, com.comet.opik.api.Visibility.PRIVATE))
                    .block();

            verify(cancellationService, never()).cancel(any(), any());
            verify(experimentService, never()).update(any(UUID.class), any());
            // Not even read: there is nothing a stop could do to it
            verify(experimentService, never()).getMetadataById(sdkLogged);
        }

        // Reproduced with ClickHouse paused: the read failed, the run was cancelled anyway, and it
        // ended permanently RUNNING with its remaining items silently skipped. A stop acts on a run
        // that may already have ended, so a read it cannot trust must not be acted on.
        @Test
        void cancelLeavesAloneAnExperimentItCannotRead() {
            var unreadable = UUID.randomUUID();

            when(experimentService.getMetadataById(unreadable))
                    .thenReturn(Mono.error(new IllegalStateException("analytics db is down")));
            // Stubbed so that acting anyway fails the verifies below, not a null dereference
            lenient().when(cancellationService.cancel(any(), any())).thenReturn(Mono.empty());
            lenient().when(cancellationService.purgeQueued(any(), any(UUID.class))).thenReturn(Mono.just(false));
            lenient().when(experimentService.update(any(UUID.class), any())).thenReturn(Mono.empty());

            service.cancel(Set.of(unreadable))
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, USER_NAME)
                            .put(RequestContext.VISIBILITY, com.comet.opik.api.Visibility.PRIVATE))
                    .block();

            // Nothing at all: no mark, so its items keep running rather than being skipped under a
            // status that was never written
            verify(cancellationService, never()).cancel(any(), any());
            verify(experimentService, never()).update(any(UUID.class), any());
        }

        // A status read only rules out what had already finished when it was taken. A consumer
        // draining its last item in the window after that read writes the run's ending, and the claim
        // is what stops the stop from writing over it — reading the status again could not, since the
        // two writes would still be racing.
        @Test
        void cancelDoesNotRelabelARunThatFinishedAsTheStopArrived() {
            var finishing = UUID.randomUUID();

            when(cancellationService.cancel(any(), any())).thenReturn(Mono.empty());
            when(cancellationService.claimFinish(WORKSPACE_ID, finishing)).thenReturn(Mono.just(false));
            when(cancellationService.purgeQueued(any(), any(UUID.class))).thenReturn(Mono.just(false));
            // Stubbed so that relabelling anyway fails the verify below, not a null dereference
            lenient().when(experimentService.update(any(UUID.class), any())).thenReturn(Mono.empty());

            service.cancel(Set.of(finishing))
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, USER_NAME)
                            .put(RequestContext.VISIBILITY, com.comet.opik.api.Visibility.PRIVATE))
                    .block();

            verify(experimentService, never()).update(eq(finishing), any());
            // Still worth stopping the queue: the outcome is written, but its items need not run
            verify(cancellationService).purgeQueued(WORKSPACE_ID, finishing);
        }

        // A prompt that completes before its siblings keeps its Stop button until the whole run
        // settles, so cancelling something already finished is one click away.
        @Test
        void cancelLeavesAnAlreadyFinishedExperimentAlone() {
            var finished = UUID.randomUUID();

            when(experimentService.getMetadataById(finished)).thenReturn(Mono.just(Experiment.builder()
                    .id(finished)
                    .datasetName("dataset")
                    .status(ExperimentStatus.COMPLETED)
                    .build()));

            service.cancel(Set.of(finished))
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, USER_NAME)
                            .put(RequestContext.VISIBILITY, com.comet.opik.api.Visibility.PRIVATE))
                    .block();

            verify(experimentService, never()).update(eq(finished), any());
            verify(cancellationService, never()).cancel(any(), any());
            verify(cancellationService, never()).purgeQueued(any(), any(UUID.class));
        }

        // A test suite whose items have drained is still RUNNING until its assertions land, and the
        // drain deliberately writes no status. Nothing has claimed the ending, so a stop can still take
        // it — this used to find the finish claimed by the drain and leave the run running for ever.
        @Test
        void cancelStopsATestSuiteStillWaitingOnItsAssertions() {
            var waiting = UUID.randomUUID();

            when(cancellationService.cancel(any(), any())).thenReturn(Mono.empty());
            when(cancellationService.claimFinish(WORKSPACE_ID, waiting)).thenReturn(Mono.just(true));
            when(cancellationService.purgeQueued(any(), any(UUID.class))).thenReturn(Mono.just(false));
            when(experimentService.update(any(UUID.class), any())).thenReturn(Mono.empty());

            service.cancel(Set.of(waiting))
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, USER_NAME)
                            .put(RequestContext.VISIBILITY, com.comet.opik.api.Visibility.PRIVATE))
                    .block();

            var captor = ArgumentCaptor.forClass(ExperimentUpdate.class);
            verify(experimentService).update(eq(waiting), captor.capture());
            assertThat(captor.getValue().status()).isEqualTo(ExperimentStatus.CANCELLED);
        }

        // Purging the last of an experiment's queued items leaves no message to reach a consumer, so
        // nothing downstream would ever record that it had stopped producing.
        @Test
        void cancelRecordsTheFinishWhenThePurgeDrainsTheExperiment() {
            var experimentId = UUID.randomUUID();

            when(cancellationService.cancel(any(), any())).thenReturn(Mono.empty());
            when(cancellationService.purgeQueued(any(), any(UUID.class))).thenReturn(Mono.just(true));
            when(experimentService.update(any(UUID.class), any())).thenReturn(Mono.empty());

            service.cancel(Set.of(experimentId))
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, USER_NAME)
                            .put(RequestContext.VISIBILITY, com.comet.opik.api.Visibility.PRIVATE))
                    .block();

            var captor = ArgumentCaptor.forClass(ExperimentUpdate.class);
            verify(experimentService, times(2)).update(eq(experimentId), captor.capture());
            assertThat(captor.getAllValues())
                    .anySatisfy(update -> assertThat(update.finished()).isTrue());
        }

        @Test
        void cancelLeavesTheFinishToTheConsumerWhileItemsAreStillOut() {
            var experimentId = UUID.randomUUID();

            when(cancellationService.cancel(any(), any())).thenReturn(Mono.empty());
            when(cancellationService.purgeQueued(any(), any(UUID.class))).thenReturn(Mono.just(false));
            when(experimentService.update(any(UUID.class), any())).thenReturn(Mono.empty());

            service.cancel(Set.of(experimentId))
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)
                            .put(RequestContext.USER_NAME, USER_NAME)
                            .put(RequestContext.VISIBILITY, com.comet.opik.api.Visibility.PRIVATE))
                    .block();

            var captor = ArgumentCaptor.forClass(ExperimentUpdate.class);
            verify(experimentService).update(eq(experimentId), captor.capture());
            assertThat(captor.getValue().finished()).isFalse();
        }
    }

    @Nested
    @DisplayName("A launch that fails partway")
    class LaunchFailure {

        @BeforeEach
        void stubRun() {
            lenient().when(idGenerator.generateId()).thenAnswer(invocation -> UUID.randomUUID());
            stubExperimentCreate();
            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            lenient().when(cancellationService.cancel(any(), any())).thenReturn(Mono.empty());
            lenient().when(cancellationService.claimFinish(any(), any(UUID.class))).thenReturn(Mono.just(true));
            lenient().when(itemPublisher.discardCounters(any())).thenReturn(Mono.empty());
            lenient().when(experimentService.update(any(UUID.class), any())).thenReturn(Mono.empty());
        }

        private ExperimentExecutionRequest request() {
            return ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildPrompt("gpt-4", "hi")))
                    .build();
        }

        @Test
        void markTheRunFailedWhenPublishingStopsPartway() {
            when(itemPublisher.publish(any(), any(), anyBoolean()))
                    .thenReturn(Mono.error(new IllegalStateException("redis is down")));

            assertThatThrownBy(() -> executeRequest(request()))
                    .as("the caller still learns the launch failed")
                    .isInstanceOf(IllegalStateException.class);

            var captor = ArgumentCaptor.forClass(ExperimentUpdate.class);
            verify(experimentService).update(any(UUID.class), captor.capture());
            assertThat(captor.getValue().status()).isEqualTo(ExperimentStatus.FAILED);
            assertThat(captor.getValue().finished())
                    .as("finished is what stamps finished_at, which is how the page stops waiting")
                    .isTrue();
        }

        // Messages may already be on the stream with no record of their ids, so they cannot be
        // purged. Marking the run is what stops a consumer calling a provider for work already lost.
        @Test
        void stopConsumersWorkingOnWhatDidGetPublished() {
            when(itemPublisher.publish(any(), any(), anyBoolean()))
                    .thenReturn(Mono.error(new IllegalStateException("redis is down")));

            assertThatThrownBy(() -> executeRequest(request())).isInstanceOf(IllegalStateException.class);

            verify(cancellationService).cancel(eq(WORKSPACE_ID), anyCollection());
        }

        // A run nobody could mark failed is bad; one later marked COMPLETED is worse. The counters
        // are discarded regardless, and a counter that is gone reads as a run that drained.
        @Test
        void keepTheClaimWhenTheRunCannotBeMarkedFailed() {
            when(itemPublisher.publish(any(), any(), anyBoolean()))
                    .thenReturn(Mono.error(new IllegalStateException("redis is down")));
            when(experimentService.update(any(UUID.class), any()))
                    .thenReturn(Mono.error(new IllegalStateException("clickhouse is down")));

            assertThatThrownBy(() -> executeRequest(request()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("redis is down");

            verify(cancellationService, never()).releaseFinish(any(), any(UUID.class));
            verify(itemPublisher).discardCounters(anyCollection());
        }

        @Test
        void discardTheCountersSoNothingHoldsAnUnreachableTotal() {
            when(itemPublisher.publish(any(), any(), anyBoolean()))
                    .thenReturn(Mono.error(new IllegalStateException("redis is down")));

            assertThatThrownBy(() -> executeRequest(request())).isInstanceOf(IllegalStateException.class);

            verify(itemPublisher).discardCounters(anyCollection());
        }

        // The claim is taken before the status is written, so an item that drains a counter late
        // cannot report the run complete over the failure.
        @Test
        void leaveTheFailureAloneWhenSomethingElseOwnsTheEnding() {
            when(itemPublisher.publish(any(), any(), anyBoolean()))
                    .thenReturn(Mono.error(new IllegalStateException("redis is down")));
            when(cancellationService.claimFinish(any(), any(UUID.class))).thenReturn(Mono.just(false));

            assertThatThrownBy(() -> executeRequest(request())).isInstanceOf(IllegalStateException.class);

            verify(experimentService, never()).update(any(UUID.class), any());
        }

        @Test
        void carryOnAbortingWhenOneRecordCannotBeMarked() {
            when(itemPublisher.publish(any(), any(), anyBoolean()))
                    .thenReturn(Mono.error(new IllegalStateException("redis is down")));
            when(experimentService.update(any(UUID.class), any()))
                    .thenReturn(Mono.error(new IllegalStateException("persistence is down")));

            assertThatThrownBy(() -> executeRequest(request()))
                    .as("the launch error, not whatever the cleanup threw")
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("redis is down");

            verify(itemPublisher).discardCounters(anyCollection());
        }
    }

    // A stop can land between the experiment rows being written and the run recording what it
    // queued. Its purge runs once, against a queue that is still empty, so the messages published
    // afterwards would be left for a consumer to walk one by one.
    @Nested
    @DisplayName("Stopped while publishing")
    class StoppedWhilePublishing {

        @BeforeEach
        void stubDataset() {
            lenient().when(idGenerator.generateId()).thenAnswer(invocation -> UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();
            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            lenient().when(cancellationService.claimFinish(any(), any(UUID.class))).thenReturn(Mono.just(true));
            lenient().when(experimentService.update(any(UUID.class), any())).thenReturn(Mono.empty());
        }

        private ExperimentExecutionRequest singlePromptRequest() {
            return ExperimentExecutionRequest.builder()
                    .datasetId(UUID.randomUUID())
                    .datasetName("test-dataset")
                    .prompts(List.of(buildPrompt("gpt-4", "hi")))
                    .build();
        }

        @Test
        void purgeTheQueueAgainOnceTheRunHasRecordedIt() {
            when(cancellationService.isCancelled(any(), any(UUID.class))).thenReturn(Mono.just(true));
            when(cancellationService.purgeQueued(any(), any(UUID.class))).thenReturn(Mono.just(false));

            executeRequest(singlePromptRequest());

            verify(cancellationService).purgeQueued(eq(WORKSPACE_ID), any(UUID.class));
        }

        // Purging everything leaves no message to reach a consumer, so nothing else would ever
        // record that the run had stopped producing.
        @Test
        void recordTheFinishWhenThatPurgeDrainsTheRun() {
            when(cancellationService.isCancelled(any(), any(UUID.class))).thenReturn(Mono.just(true));
            when(cancellationService.purgeQueued(any(), any(UUID.class))).thenReturn(Mono.just(true));

            executeRequest(singlePromptRequest());

            var captor = ArgumentCaptor.forClass(ExperimentUpdate.class);
            verify(experimentService).update(any(UUID.class), captor.capture());
            assertThat(captor.getValue().finished()).isTrue();
            assertThat(captor.getValue().status()).isNull();
        }

        @Test
        void leaveTheFinishToTheConsumerWhenItemsAreStillOut() {
            when(cancellationService.isCancelled(any(), any(UUID.class))).thenReturn(Mono.just(true));
            when(cancellationService.purgeQueued(any(), any(UUID.class))).thenReturn(Mono.just(false));

            executeRequest(singlePromptRequest());

            verify(cancellationService).purgeQueued(eq(WORKSPACE_ID), any(UUID.class));
            verify(experimentService, never()).update(any(UUID.class), any());
        }

        @Test
        void leaveARunNobodyStoppedAlone() {
            executeRequest(singlePromptRequest());

            verify(cancellationService, never()).purgeQueued(any(), any(UUID.class));
        }

        // Stop is offered per prompt, so one variant can be stopped while its siblings run on. The
        // purge is chosen per experiment, and a single-prompt run cannot tell that apart from one
        // chosen for the whole batch.
        @Test
        void purgeOnlyTheSiblingThatWasStopped() {
            var stopped = UUID.randomUUID();
            var running = UUID.randomUUID();
            when(idGenerator.generateId()).thenReturn(stopped, running, UUID.randomUUID());

            when(cancellationService.isCancelled(WORKSPACE_ID, stopped)).thenReturn(Mono.just(true));
            when(cancellationService.isCancelled(WORKSPACE_ID, running)).thenReturn(Mono.just(false));
            when(cancellationService.purgeQueued(any(), any(UUID.class))).thenReturn(Mono.just(false));

            executeRequest(ExperimentExecutionRequest.builder()
                    .datasetId(UUID.randomUUID())
                    .datasetName("test-dataset")
                    .prompts(List.of(buildPrompt("gpt-4", "hi"), buildPrompt("gpt-4", "there")))
                    .build());

            verify(cancellationService).purgeQueued(WORKSPACE_ID, stopped);
            verify(cancellationService, never()).purgeQueued(WORKSPACE_ID, running);
        }

        // The run is cancelled either way: the mark is set before the purge, so whatever this
        // misses a consumer skips. Failing the request over a lost optimisation would be worse.
        @Test
        void returnTheRunEvenWhenThatPurgeFails() {
            when(cancellationService.isCancelled(any(), any(UUID.class))).thenReturn(Mono.just(true));
            when(cancellationService.purgeQueued(any(), any(UUID.class)))
                    .thenReturn(Mono.error(new IllegalStateException("redis down")));

            var response = executeRequest(singlePromptRequest());

            verify(cancellationService).purgeQueued(eq(WORKSPACE_ID), any(UUID.class));
            assertThat(response.experiments()).hasSize(1);
        }
    }

    @Nested
    @DisplayName("Dataset item streaming")
    class DatasetItemStreaming {

        private static final int STREAM_PAGE_SIZE = 2000;

        // The items are read from the resolved version, so the message has to say so too: a message
        // carrying null leaves the assertion sampler to score against whatever is latest when it
        // runs, which is the same disagreement one step further on.
        @Test
        @SuppressWarnings("unchecked")
        void createAndExecutePutsTheResolvedVersionOnEveryMessage() {
            var datasetId = UUID.randomUUID();
            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(datasetVersionService.getLatestVersion(datasetId, WORKSPACE_ID))
                    .thenReturn(Optional.of(DatasetVersion.builder()
                            .versionHash("the-latest-hash")
                            .executionPolicy(new ExecutionPolicy(1, 1))
                            .build()));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(datasetId)
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build());

            var captor = ArgumentCaptor.forClass(List.class);
            verify(itemPublisher).publish(any(UUID.class), captor.capture(), anyBoolean());
            assertThat((List<ExperimentItemToProcess>) captor.getValue())
                    .isNotEmpty()
                    .allSatisfy(message -> assertThat(message.versionHash()).isEqualTo("the-latest-hash"));
        }

        // The policy was read from the latest version while the items streamed from whatever the
        // dataset held at the time, so the two could describe different versions - and a dataset
        // changing mid-run could move items between pages. One read now answers both.
        @Test
        void createAndExecuteStreamsTheVersionItTookThePolicyFrom() {
            var datasetId = UUID.randomUUID();
            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(datasetVersionService.getLatestVersion(datasetId, WORKSPACE_ID))
                    .thenReturn(Optional.of(DatasetVersion.builder()
                            .versionHash("the-latest-hash")
                            .executionPolicy(new ExecutionPolicy(1, 1))
                            .build()));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(datasetId)
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build());

            var captor = ArgumentCaptor.forClass(DatasetItemStreamRequest.class);
            verify(datasetItemService, atLeastOnce()).getItems(captor.capture(), any());
            assertThat(captor.getAllValues())
                    .allSatisfy(streamed -> assertThat(streamed.datasetVersion()).isEqualTo("the-latest-hash"));
        }

        @Test
        void createAndExecuteStreamsThePinnedVersionWhenOneWasAskedFor() {
            var datasetId = UUID.randomUUID();
            var versionId = UUID.randomUUID();
            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(datasetVersionService.resolveVersionId(WORKSPACE_ID, datasetId, "pinned-hash"))
                    .thenReturn(versionId);
            when(datasetVersionService.getVersionById(WORKSPACE_ID, datasetId, versionId))
                    .thenReturn(DatasetVersion.builder().executionPolicy(new ExecutionPolicy(1, 1)).build());
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(datasetId)
                    .versionHash("pinned-hash")
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build());

            var captor = ArgumentCaptor.forClass(DatasetItemStreamRequest.class);
            verify(datasetItemService, atLeastOnce()).getItems(captor.capture(), any());
            assertThat(captor.getAllValues())
                    .allSatisfy(streamed -> assertThat(streamed.datasetVersion()).isEqualTo("pinned-hash"));
            verify(datasetVersionService, never()).getLatestVersion(any(UUID.class), any());
        }

        // Reading items by hash goes to the versioned table ahead of the flag the rest of that read
        // honours. Where versioning is off the writes land in the legacy table, so pinning would run
        // the dataset against a snapshot instead of its data.
        @Test
        void createAndExecuteStreamsLiveWhenVersioningIsOff() {
            var datasetId = UUID.randomUUID();
            when(featureFlags.isDatasetVersioningEnabled()).thenReturn(false);
            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(datasetVersionService.getLatestVersion(datasetId, WORKSPACE_ID))
                    .thenReturn(Optional.of(DatasetVersion.builder()
                            .versionHash("a-stale-snapshot")
                            .executionPolicy(new ExecutionPolicy(3, 1))
                            .build()));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(datasetId)
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build());

            var captor = ArgumentCaptor.forClass(DatasetItemStreamRequest.class);
            verify(datasetItemService, atLeastOnce()).getItems(captor.capture(), any());
            assertThat(captor.getAllValues())
                    .as("the items must come from the legacy read, not the version")
                    .allSatisfy(streamed -> assertThat(streamed.datasetVersion()).isNull());
        }

        // A dataset with no versions at all has nothing to pin to, and streams live as it always did.
        @Test
        void createAndExecuteStreamsLiveWhenTheDatasetHasNoVersion() {
            var datasetId = UUID.randomUUID();
            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(datasetVersionService.getLatestVersion(datasetId, WORKSPACE_ID)).thenReturn(Optional.empty());
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(datasetId)
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build());

            var captor = ArgumentCaptor.forClass(DatasetItemStreamRequest.class);
            verify(datasetItemService, atLeastOnce()).getItems(captor.capture(), any());
            assertThat(captor.getValue().datasetVersion()).isNull();
        }

        private ExperimentExecutionRequest buildRequest(List<UUID> selectedRuleIds, String filters) {
            return ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .selectedRuleIds(selectedRuleIds)
                    .filters(filters)
                    .build();
        }

        @Test
        void createAndExecutePagesPastTheSingleCallLimit() {
            var firstPage = IntStream.range(0, STREAM_PAGE_SIZE)
                    .mapToObj(i -> buildDatasetItem(UUID.randomUUID(), null))
                    .toList();
            var secondPage = List.of(buildDatasetItem(UUID.randomUUID(), null));

            when(datasetItemService.getItems(any(DatasetItemStreamRequest.class), any()))
                    .thenReturn(Flux.fromIterable(firstPage))
                    .thenReturn(Flux.fromIterable(secondPage));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            var response = executeRequest(buildRequest(null, null));

            assertThat(response.totalItems()).isEqualTo(STREAM_PAGE_SIZE + 1);

            var captor = ArgumentCaptor.forClass(DatasetItemStreamRequest.class);
            verify(datasetItemService, times(2)).getItems(captor.capture(), any());

            var requests = captor.getAllValues();
            assertThat(requests.getFirst().steamLimit()).isEqualTo(STREAM_PAGE_SIZE);
            assertThat(requests.getFirst().lastRetrievedId()).isNull();
            assertThat(requests.get(1).lastRetrievedId()).isEqualTo(firstPage.getLast().id());
        }

        @Test
        void createAndExecuteStopsPagingOnAShortPage() {
            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(buildRequest(null, null));

            verify(datasetItemService, times(1)).getItems(any(DatasetItemStreamRequest.class), any());
        }

        @Test
        void createAndExecutePassesDatasetItemFiltersThrough() {
            List<DatasetItemFilter> filters = List.of(DatasetItemFilter.builder()
                    .field(DatasetItemField.DATA)
                    .key("input")
                    .operator(Operator.CONTAINS)
                    .value("hello")
                    .build());

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(buildRequest(null, "[]"), filters);

            verify(datasetItemService).getItems(any(DatasetItemStreamRequest.class), eq(filters));
        }

        @Test
        void createAndExecutePutsSelectedRuleIdsOnEveryMessage() {
            var ruleIds = List.of(UUID.randomUUID(), UUID.randomUUID());

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(buildRequest(ruleIds, null));

            var captor = ArgumentCaptor.<List<ExperimentItemToProcess>>captor();
            verify(itemPublisher).publish(any(UUID.class), captor.capture(), anyBoolean());

            assertThat(captor.getValue())
                    .isNotEmpty()
                    .allSatisfy(message -> assertThat(message.selectedRuleIds()).isEqualTo(ruleIds));
        }
    }

    @Nested
    @DisplayName("Dataset type propagation")
    class DatasetTypePropagation {

        private List<ExperimentItemToProcess> runAndCaptureMessages(DatasetType type) {
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build();

            stubDatasetType(type);
            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(request);

            var captor = ArgumentCaptor.<List<ExperimentItemToProcess>>captor();
            verify(itemPublisher).publish(any(UUID.class), captor.capture(), anyBoolean());
            return captor.getValue();
        }

        @Test
        void createAndExecuteMarksMessagesFromARegularDatasetAsNotTestSuite() {
            assertThat(runAndCaptureMessages(DatasetType.DATASET))
                    .allSatisfy(message -> assertThat(message.isTestSuite()).isFalse());
        }

        @Test
        void createAndExecuteMarksMessagesFromATestSuiteAsTestSuite() {
            assertThat(runAndCaptureMessages(DatasetType.TEST_SUITE))
                    .allSatisfy(message -> assertThat(message.isTestSuite()).isTrue());
        }

        @Test
        void createAndExecuteSkipsAssertionCountersForARegularDataset() {
            runAndCaptureMessages(DatasetType.DATASET);

            verify(itemPublisher).publish(any(UUID.class), any(), eq(false));
        }
    }

    @Nested
    @DisplayName("opik_prompts resolution")
    class OpikPromptsResolution {

        private ExperimentExecutionRequest.PromptVariant buildVariantWithVersions(String model,
                List<Experiment.PromptVersionLink> versions) {
            return ExperimentExecutionRequest.PromptVariant.builder()
                    .model(model)
                    .messages(List.of(ExperimentExecutionRequest.PromptVariant.Message.builder()
                            .role("user")
                            .content(new TextNode("Hello"))
                            .build()))
                    .promptVersions(versions)
                    .build();
        }

        @SuppressWarnings("unchecked")
        private List<ExperimentItemToProcess> capturePublishedMessages() {
            var captor = ArgumentCaptor.forClass(List.class);
            verify(itemPublisher).publish(any(UUID.class), captor.capture(), anyBoolean());
            return (List<ExperimentItemToProcess>) captor.getValue();
        }

        @Test
        void createAndExecuteResolvesPromptVersionsOncePerRequest() {
            var versionId1 = UUID.randomUUID();
            var versionId2 = UUID.randomUUID();
            var promptId1 = UUID.randomUUID();
            var promptId2 = UUID.randomUUID();
            var name1 = RandomStringUtils.randomAlphanumeric(10);
            var name2 = RandomStringUtils.randomAlphanumeric(10);

            var v1 = PromptVersion.builder().id(versionId1).promptId(promptId1)
                    .commit(RandomStringUtils.randomAlphanumeric(8))
                    .versionNumber("v1").template(RandomStringUtils.randomAlphanumeric(20))
                    .templateStructure(TemplateStructure.TEXT).build();
            var v2 = PromptVersion.builder().id(versionId2).promptId(promptId2)
                    .commit(RandomStringUtils.randomAlphanumeric(8))
                    .versionNumber("v2").template(RandomStringUtils.randomAlphanumeric(20))
                    .templateStructure(TemplateStructure.CHAT).build();

            var link1 = Experiment.PromptVersionLink.builder()
                    .id(versionId1).promptId(promptId1).promptName(name1).build();
            var link2 = Experiment.PromptVersionLink.builder()
                    .id(versionId2).promptId(promptId2).promptName(name2).build();

            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(
                            buildVariantWithVersions("gpt-4", List.of(link1)),
                            buildVariantWithVersions("gpt-4", List.of(link2))))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null),
                    buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();
            when(promptService.findVersionByIds(Set.of(versionId1, versionId2)))
                    .thenReturn(Mono.just(Map.of(versionId1, v1, versionId2, v2)));

            executeRequest(request);

            // The lookup is invoked exactly once for the whole request, regardless of how
            // many dataset items get published.
            verify(promptService).findVersionByIds(Set.of(versionId1, versionId2));

            var messages = capturePublishedMessages();
            assertThat(messages).hasSize(4); // 2 items x 2 variants

            // Each message carries the prebuilt opik_prompts array for its variant.
            for (var msg : messages) {
                assertThat(msg.opikPrompts()).isNotNull();
                assertThat(msg.opikPrompts()).hasSize(1);
            }
            // Variant 0 messages reference link1; variant 1 messages reference link2.
            var firstVariantMessages = messages.stream()
                    .filter(m -> name1.equals(m.opikPrompts().get(0).name())).toList();
            var secondVariantMessages = messages.stream()
                    .filter(m -> name2.equals(m.opikPrompts().get(0).name())).toList();
            assertThat(firstVariantMessages).hasSize(2);
            assertThat(secondVariantMessages).hasSize(2);
        }

        @Test
        void createAndExecuteResolvesPromptNameFromBulkLookupWhenLinkOmitsIt() {
            // FE flow: the request carries PromptVersionLinks with only `id` (and `prompt_id`),
            // no `prompt_name`. The bulk getVersionsInfoByVersionsIds lookup must supply it so
            // the persisted opik_prompts[].name isn't null in trace metadata.
            var versionId = UUID.randomUUID();
            var promptId = UUID.randomUUID();
            var resolvedName = RandomStringUtils.randomAlphanumeric(10);

            var version = PromptVersion.builder().id(versionId).promptId(promptId)
                    .commit(RandomStringUtils.randomAlphanumeric(8))
                    .versionNumber("v1").template(RandomStringUtils.randomAlphanumeric(20))
                    .templateStructure(TemplateStructure.TEXT).build();
            // No promptName on the link — mirrors what the FE sends.
            var link = Experiment.PromptVersionLink.builder()
                    .id(versionId).promptId(promptId).build();

            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildVariantWithVersions("gpt-4", List.of(link))))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();
            when(promptService.findVersionByIds(Set.of(versionId)))
                    .thenReturn(Mono.just(Map.of(versionId, version)));
            when(promptService.getVersionsInfoByVersionsIds(Set.of(versionId)))
                    .thenReturn(Mono.just(Map.of(versionId,
                            new PromptVersionInfo(versionId, version.commit(), version.versionNumber(),
                                    resolvedName))));

            executeRequest(request);

            var entry = capturePublishedMessages().get(0).opikPrompts().get(0);
            assertThat(entry.name()).isEqualTo(resolvedName);
            assertThat(entry.id()).isEqualTo(promptId);
        }

        @Test
        void createAndExecuteSkipsLookupWhenNoPromptVersionsLinked() {
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            executeRequest(request);

            verify(promptService, never()).findVersionByIds(any());
            var messages = capturePublishedMessages();
            assertThat(messages).allSatisfy(m -> assertThat(m.opikPrompts()).isEmpty());
        }

        @Test
        void createAndExecutePreservesTextTemplateAsRawString() {
            // TEXT prompts: even when the template happens to look like valid JSON (e.g. "42"),
            // it must round-trip as a string, not be eagerly parsed into a primitive node.
            var versionId = UUID.randomUUID();
            var promptId = UUID.randomUUID();
            var version = PromptVersion.builder()
                    .id(versionId).promptId(promptId)
                    .commit(RandomStringUtils.randomAlphanumeric(8)).versionNumber("v1")
                    .template("42")
                    .templateStructure(TemplateStructure.TEXT)
                    .build();
            var link = Experiment.PromptVersionLink.builder()
                    .id(versionId).promptId(promptId)
                    .promptName(RandomStringUtils.randomAlphanumeric(10)).build();

            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildVariantWithVersions("gpt-4", List.of(link))))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();
            when(promptService.findVersionByIds(Set.of(versionId)))
                    .thenReturn(Mono.just(Map.of(versionId, version)));

            executeRequest(request);

            var entry = capturePublishedMessages().get(0).opikPrompts().get(0);
            var template = entry.version().template();
            assertThat(template.isTextual()).isTrue();
            assertThat(template.asText()).isEqualTo("42");
        }

        @Test
        void createAndExecuteParsesChatTemplateAsStructuredJson() {
            // CHAT prompts: the stored string is a JSON-encoded list of messages. The wire
            // format expects it to appear as a parsed array/object, matching the Python SDK.
            var role = RandomStringUtils.randomAlphanumeric(8);
            var content = RandomStringUtils.randomAlphanumeric(20);
            var chatJson = "[{\"role\":\"" + role + "\",\"content\":\"" + content + "\"}]";
            var versionId = UUID.randomUUID();
            var promptId = UUID.randomUUID();
            var version = PromptVersion.builder()
                    .id(versionId).promptId(promptId)
                    .commit(RandomStringUtils.randomAlphanumeric(8)).versionNumber("v1")
                    .template(chatJson)
                    .templateStructure(TemplateStructure.CHAT)
                    .build();
            var link = Experiment.PromptVersionLink.builder()
                    .id(versionId).promptId(promptId)
                    .promptName(RandomStringUtils.randomAlphanumeric(10)).build();

            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildVariantWithVersions("gpt-4", List.of(link))))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();
            when(promptService.findVersionByIds(Set.of(versionId)))
                    .thenReturn(Mono.just(Map.of(versionId, version)));

            executeRequest(request);

            var entry = capturePublishedMessages().get(0).opikPrompts().get(0);
            var template = entry.version().template();
            assertThat(template.isArray()).isTrue();
            assertThat(template).hasSize(1);
            assertThat(template.get(0).get("role").asText()).isEqualTo(role);
            assertThat(template.get(0).get("content").asText()).isEqualTo(content);
        }

        @Test
        void createAndExecuteFallsBackToStringWhenChatTemplateIsNotValidJson() {
            // Defensive: a CHAT template that fails to parse should fall back to a string
            // rather than failing the experiment.
            var raw = RandomStringUtils.randomAlphanumeric(15);
            var versionId = UUID.randomUUID();
            var promptId = UUID.randomUUID();
            var version = PromptVersion.builder()
                    .id(versionId).promptId(promptId)
                    .commit(RandomStringUtils.randomAlphanumeric(8)).versionNumber("v1")
                    .template(raw)
                    .templateStructure(TemplateStructure.CHAT)
                    .build();
            var link = Experiment.PromptVersionLink.builder()
                    .id(versionId).promptId(promptId)
                    .promptName(RandomStringUtils.randomAlphanumeric(10)).build();

            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildVariantWithVersions("gpt-4", List.of(link))))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();
            when(promptService.findVersionByIds(Set.of(versionId)))
                    .thenReturn(Mono.just(Map.of(versionId, version)));

            executeRequest(request);

            var entry = capturePublishedMessages().get(0).opikPrompts().get(0);
            var template = entry.version().template();
            assertThat(template.isTextual()).isTrue();
            assertThat(template.asText()).isEqualTo(raw);
        }

        @Test
        void createAndExecuteStillEmitsEntriesWhenVersionInfoLookupFails() {
            // Partial-failure path: if the bulk name lookup fails (e.g. DB timeout) we still
            // emit version-based entries with null name rather than dropping all opik_prompts.
            var versionId = UUID.randomUUID();
            var promptId = UUID.randomUUID();
            var version = PromptVersion.builder().id(versionId).promptId(promptId)
                    .commit(RandomStringUtils.randomAlphanumeric(8))
                    .versionNumber("v1").template(RandomStringUtils.randomAlphanumeric(20))
                    .templateStructure(TemplateStructure.TEXT).build();
            var link = Experiment.PromptVersionLink.builder()
                    .id(versionId).promptId(promptId).build();

            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildVariantWithVersions("gpt-4", List.of(link))))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();
            when(promptService.findVersionByIds(Set.of(versionId)))
                    .thenReturn(Mono.just(Map.of(versionId, version)));
            when(promptService.getVersionsInfoByVersionsIds(Set.of(versionId)))
                    .thenReturn(Mono.error(new RuntimeException("info lookup failed")));

            executeRequest(request);

            var entry = capturePublishedMessages().get(0).opikPrompts().get(0);
            assertThat(entry.id()).isEqualTo(promptId);
            assertThat(entry.name()).isNull();
            assertThat(entry.version().id()).isEqualTo(versionId);
        }

        @Test
        void createAndExecuteFallsBackToNullArraysWhenLookupFails() {
            var versionId = UUID.randomUUID();
            var link = Experiment.PromptVersionLink.builder()
                    .id(versionId).promptId(UUID.randomUUID())
                    .promptName(RandomStringUtils.randomAlphanumeric(10)).build();

            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildVariantWithVersions("gpt-4", List.of(link))))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();
            when(promptService.findVersionByIds(Set.of(versionId)))
                    .thenReturn(Mono.error(new RuntimeException("db unavailable")));

            executeRequest(request);

            var messages = capturePublishedMessages();
            assertThat(messages).allSatisfy(m -> assertThat(m.opikPrompts()).isEmpty());
        }
    }
}
