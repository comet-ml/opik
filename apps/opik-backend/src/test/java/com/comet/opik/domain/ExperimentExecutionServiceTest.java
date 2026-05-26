package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.EvaluationMethod;
import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.ExperimentExecutionResponse;
import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.TemplateStructure;
import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.api.resources.v1.events.TestSuiteEvaluatorMapper;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.comet.opik.infrastructure.TestSuiteConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
                experimentService, datasetItemService, datasetVersionService,
                itemPublisher, idGenerator, evaluatorMapper, new ExperimentExecutionConfig(), promptService);

        lenient().when(itemPublisher.publish(any(), any())).thenReturn(Mono.empty());
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
        return service.createAndExecute(request)
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
            verify(itemPublisher).publish(any(UUID.class), captor.capture());
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
