package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.EvaluationMethod;
import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.ExperimentStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
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
    private DatasetItemService datasetItemService;

    @Mock
    private DatasetVersionService datasetVersionService;

    @Mock
    private ExperimentItemProcessor itemProcessor;

    @Mock
    private IdGenerator idGenerator;

    private ExperimentExecutionService service;

    @BeforeEach
    void setUp() {
        service = new ExperimentExecutionService(
                experimentService, datasetItemService, datasetVersionService,
                itemProcessor, idGenerator);
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

    private DatasetItem buildDatasetItem(UUID id, ExecutionPolicy executionPolicy) {
        return DatasetItem.builder()
                .id(id)
                .data(Map.of("input", new TextNode("hello")))
                .executionPolicy(executionPolicy)
                .build();
    }

    private void stubDatasetItems(List<DatasetItem> items) {
        when(datasetItemService.getItems(eq(WORKSPACE_ID), any(DatasetItemStreamRequest.class), any()))
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
        void createAndExecuteWhenNoDatasetItemsReturnsEmptyResponse() {
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .prompts(List.of(buildPrompt("gpt-4", "Hello {{input}}")))
                    .build();

            stubDatasetItems(List.of());

            var response = service.createAndExecute(request, WORKSPACE_ID, USER_NAME);

            assertThat(response.experiments()).isEmpty();
            assertThat(response.totalItems()).isZero();
            verify(experimentService, never()).create(any());
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

            var response = service.createAndExecute(request, WORKSPACE_ID, USER_NAME);

            assertThat(response.experiments()).hasSize(2);
            assertThat(response.experiments().getFirst().promptIndex()).isZero();
            assertThat(response.experiments().get(1).promptIndex()).isEqualTo(1);
            assertThat(response.experiments().getFirst().experimentId()).isEqualTo(expId1);
            assertThat(response.experiments().get(1).experimentId()).isEqualTo(expId2);
        }

        @Test
        void createAndExecuteSetsEvaluationSuiteMethodAndRunningStatus() {
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .datasetId(UUID.randomUUID())
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
            stubExperimentCreate();
            stubFinishExperiments();

            service.createAndExecute(request, WORKSPACE_ID, USER_NAME);

            var captor = ArgumentCaptor.forClass(Experiment.class);
            verify(experimentService).create(captor.capture());

            var experiment = captor.getValue();
            assertThat(experiment.evaluationMethod()).isEqualTo(EvaluationMethod.EVALUATION_SUITE);
            assertThat(experiment.status()).isEqualTo(ExperimentStatus.RUNNING);
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

            service.createAndExecute(request, WORKSPACE_ID, USER_NAME);

            var captor = ArgumentCaptor.forClass(Experiment.class);
            verify(experimentService).create(captor.capture());
            assertThat(captor.getValue().projectName()).isEqualTo("my-project");
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

            service.createAndExecute(request, WORKSPACE_ID, USER_NAME);

            var captor = ArgumentCaptor.forClass(Experiment.class);
            verify(experimentService).create(captor.capture());
            assertThat(captor.getValue().projectName()).isEqualTo("playground");
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

            var response = service.createAndExecute(request, WORKSPACE_ID, USER_NAME);

            assertThat(response.totalItems()).isEqualTo(3);
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

            var response = service.createAndExecute(request, WORKSPACE_ID, USER_NAME);

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

            var response = service.createAndExecute(request, WORKSPACE_ID, USER_NAME);

            assertThat(response.totalItems()).isEqualTo(8);
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

            var response = service.createAndExecute(request, WORKSPACE_ID, USER_NAME);

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

            var response = service.createAndExecute(request, WORKSPACE_ID, USER_NAME);

            assertThat(response.totalItems()).isEqualTo(2);
            verify(datasetVersionService).resolveVersionId(WORKSPACE_ID, datasetId, versionHash);
            verify(datasetVersionService).getVersionById(WORKSPACE_ID, datasetId, versionId);
            verify(datasetVersionService, never()).getLatestVersion(any(), any());
        }

        @Test
        void createAndExecuteThrowsWhenNoDatasetId() {
            var request = ExperimentExecutionRequest.builder()
                    .datasetName("test-dataset")
                    .prompts(List.of(buildPrompt("gpt-4", "Hello")))
                    .build();

            stubDatasetItems(List.of(buildDatasetItem(UUID.randomUUID(), null)));

            org.assertj.core.api.Assertions.assertThatThrownBy(
                    () -> service.createAndExecute(request, WORKSPACE_ID, USER_NAME))
                    .isInstanceOf(jakarta.ws.rs.BadRequestException.class)
                    .hasMessageContaining("Dataset ID is required");
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

            service.createAndExecute(request, WORKSPACE_ID, USER_NAME);

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

            service.createAndExecute(request, WORKSPACE_ID, USER_NAME);

            var captor = ArgumentCaptor.forClass(Experiment.class);
            verify(experimentService).create(captor.capture());

            var metadata = captor.getValue().metadata();
            assertThat(metadata.has("model_config")).isTrue();
        }
    }
}
