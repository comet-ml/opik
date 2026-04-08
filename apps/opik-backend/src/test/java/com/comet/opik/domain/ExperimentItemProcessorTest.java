package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.Source;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.template.MustacheParser;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.langchain4j.model.openai.internal.chat.AssistantMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionChoice;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.chat.SystemMessage;
import dev.langchain4j.model.openai.internal.shared.Usage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("ExperimentItemProcessor Test")
class ExperimentItemProcessorTest {

    private static final String WORKSPACE_ID = "test-workspace-id";
    private static final String USER_NAME = "test-user";
    private static final String PROJECT_NAME = "test-project";

    @Mock
    private ChatCompletionService chatCompletionService;

    @Mock
    private LlmProviderFactory llmProviderFactory;

    @Mock
    private TraceService traceService;

    @Mock
    private SpanService spanService;

    @Mock
    private ExperimentItemService experimentItemService;

    @Mock
    private DatasetItemService datasetItemService;

    @Mock
    private IdGenerator idGenerator;

    @Mock
    private MustacheParser mustacheParser;

    private ExperimentItemProcessor processor;

    @BeforeEach
    void setUp() {
        var messageRenderer = new ExperimentMessageRenderer(mustacheParser);
        var tracePersistence = new ExperimentTracePersistence(
                traceService, spanService, experimentItemService, llmProviderFactory, idGenerator);
        processor = new ExperimentItemProcessor(
                chatCompletionService, messageRenderer, tracePersistence, datasetItemService, idGenerator);
    }

    private ExperimentExecutionRequest.PromptVariant buildPrompt(String model, String role, String content) {
        return ExperimentExecutionRequest.PromptVariant.builder()
                .model(model)
                .messages(List.of(
                        ExperimentExecutionRequest.PromptVariant.Message.builder()
                                .role(role)
                                .content(new TextNode(content))
                                .build()))
                .build();
    }

    private DatasetItem buildDatasetItem(UUID id, Map<String, JsonNode> data) {
        return DatasetItem.builder()
                .id(id)
                .data(data)
                .build();
    }

    private ChatCompletionResponse buildLlmResponse(String content) {
        return ChatCompletionResponse.builder()
                .choices(List.of(ChatCompletionChoice.builder()
                        .message(AssistantMessage.builder().content(content).build())
                        .build()))
                .usage(Usage.builder()
                        .promptTokens(10)
                        .completionTokens(20)
                        .totalTokens(30)
                        .build())
                .build();
    }

    private void stubCommonMocks() {
        when(idGenerator.generateId()).thenReturn(UUID.randomUUID(), UUID.randomUUID());
        when(llmProviderFactory.getResolvedModelInfo("gpt-4"))
                .thenReturn(new LlmProviderFactory.ResolvedModelInfo("gpt-4", "openai"));
        when(traceService.create(any(Trace.class))).thenReturn(Mono.just(UUID.randomUUID()));
        when(spanService.create(any(Span.class))).thenReturn(Mono.just(UUID.randomUUID()));
        when(experimentItemService.create(any())).thenReturn(Mono.empty());
        when(mustacheParser.renderUnescaped(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private ExperimentItemToProcess buildMessage(
            ExperimentExecutionRequest.PromptVariant prompt,
            DatasetItem datasetItem,
            UUID experimentId,
            UUID datasetId,
            String versionHash,
            String projectName,
            String workspaceId,
            String userName) {
        when(datasetItemService.get(datasetItem.id())).thenReturn(Mono.just(datasetItem));
        return ExperimentItemToProcess.builder()
                .batchId(UUID.randomUUID())
                .prompt(prompt)
                .datasetItemId(datasetItem.id())
                .experimentId(experimentId)
                .datasetId(datasetId)
                .versionHash(versionHash)
                .projectName(projectName)
                .workspaceId(workspaceId)
                .userName(userName)
                .allExperimentIds(List.of(experimentId))
                .build();
    }

    @Nested
    @DisplayName("Template rendering")
    class TemplateRendering {

        @Test
        void processCallsRenderUnescapedWithTemplateAndContext() {
            var prompt = buildPrompt("gpt-4", "user", "Hello {{name}}, how is {{city}}?");
            var datasetItem = buildDatasetItem(UUID.randomUUID(),
                    Map.of("name", new TextNode("Alice"), "city", new TextNode("London")));
            var experimentId = UUID.randomUUID();
            var datasetId = UUID.randomUUID();

            stubCommonMocks();
            when(mustacheParser.renderUnescaped(eq("Hello {{name}}, how is {{city}}?"), any()))
                    .thenReturn("Hello Alice, how is London?");
            when(chatCompletionService.create(any(ChatCompletionRequest.class), eq(WORKSPACE_ID)))
                    .thenReturn(buildLlmResponse("Hi Alice!"));

            processor.process(buildMessage(prompt, datasetItem, experimentId, datasetId, null,
                    PROJECT_NAME, WORKSPACE_ID, USER_NAME)).block();

            @SuppressWarnings("unchecked")
            var contextCaptor = ArgumentCaptor.forClass(Map.class);
            verify(mustacheParser).renderUnescaped(eq("Hello {{name}}, how is {{city}}?"), contextCaptor.capture());

            var context = contextCaptor.getValue();
            assertThat(context).containsEntry("name", "Alice");
            assertThat(context).containsEntry("city", "London");
        }

        @Test
        void processUsesRenderedContentInChatRequest() {
            var prompt = buildPrompt("gpt-4", "user", "{{content}}");
            var datasetItem = buildDatasetItem(UUID.randomUUID(),
                    Map.of("content", new TextNode("<b>bold</b> & 'quoted'")));
            var experimentId = UUID.randomUUID();
            var datasetId = UUID.randomUUID();

            stubCommonMocks();
            when(mustacheParser.renderUnescaped(eq("{{content}}"), any()))
                    .thenReturn("<b>bold</b> & 'quoted'");
            when(chatCompletionService.create(any(ChatCompletionRequest.class), eq(WORKSPACE_ID)))
                    .thenReturn(buildLlmResponse("response"));

            processor.process(buildMessage(prompt, datasetItem, experimentId, datasetId, null,
                    PROJECT_NAME, WORKSPACE_ID, USER_NAME)).block();

            var traceCaptor = ArgumentCaptor.forClass(Trace.class);
            verify(traceService).create(traceCaptor.capture());

            var traceInput = traceCaptor.getValue().input();
            var messagesNode = traceInput.get("messages");
            var contentNode = messagesNode.get(0).get("content");
            assertThat(contentNode.asText()).contains("<b>bold</b>");
            assertThat(contentNode.asText()).contains("&");
        }
    }

    @Nested
    @DisplayName("Trace creation")
    class TraceCreation {

        @Test
        void processCreatesTraceWithEvalSuiteMetadata() {
            var prompt = buildPrompt("gpt-4", "user", "Hello");
            var datasetItemId = UUID.randomUUID();
            var datasetItem = buildDatasetItem(datasetItemId, Map.of("input", new TextNode("test")));
            var experimentId = UUID.randomUUID();
            var datasetId = UUID.randomUUID();
            var versionHash = "hash123";

            stubCommonMocks();
            when(chatCompletionService.create(any(ChatCompletionRequest.class), eq(WORKSPACE_ID)))
                    .thenReturn(buildLlmResponse("response"));

            processor.process(buildMessage(prompt, datasetItem, experimentId, datasetId, versionHash,
                    PROJECT_NAME, WORKSPACE_ID, USER_NAME)).block();

            var captor = ArgumentCaptor.forClass(Trace.class);
            verify(traceService).create(captor.capture());

            var trace = captor.getValue();
            var metadata = trace.metadata();

            assertThat(metadata.get("created_from").asText()).isEqualTo("playground");
            assertThat(metadata.get("eval_suite_dataset_id").asText()).isEqualTo(datasetId.toString());
            assertThat(metadata.get("eval_suite_dataset_version_hash").asText()).isEqualTo(versionHash);
            assertThat(metadata.get("eval_suite_dataset_item_id").asText()).isEqualTo(datasetItemId.toString());
        }

        @Test
        void processCreatesTraceWithExperimentSource() {
            var prompt = buildPrompt("gpt-4", "user", "Hello");
            var datasetItem = buildDatasetItem(UUID.randomUUID(), Map.of());
            var experimentId = UUID.randomUUID();
            var datasetId = UUID.randomUUID();

            stubCommonMocks();
            when(chatCompletionService.create(any(ChatCompletionRequest.class), eq(WORKSPACE_ID)))
                    .thenReturn(buildLlmResponse("response"));

            processor.process(buildMessage(prompt, datasetItem, experimentId, datasetId, null,
                    PROJECT_NAME, WORKSPACE_ID, USER_NAME)).block();

            var captor = ArgumentCaptor.forClass(Trace.class);
            verify(traceService).create(captor.capture());

            assertThat(captor.getValue().source()).isEqualTo(Source.EXPERIMENT);
        }

        @Test
        void processCreatesTraceWithCorrectName() {
            var prompt = buildPrompt("gpt-4", "user", "Hello");
            var datasetItem = buildDatasetItem(UUID.randomUUID(), Map.of());
            var experimentId = UUID.randomUUID();
            var datasetId = UUID.randomUUID();

            stubCommonMocks();
            when(chatCompletionService.create(any(ChatCompletionRequest.class), eq(WORKSPACE_ID)))
                    .thenReturn(buildLlmResponse("response"));

            processor.process(buildMessage(prompt, datasetItem, experimentId, datasetId, null,
                    PROJECT_NAME, WORKSPACE_ID, USER_NAME)).block();

            var captor = ArgumentCaptor.forClass(Trace.class);
            verify(traceService).create(captor.capture());

            assertThat(captor.getValue().name()).isEqualTo("chat_completion_create");
        }

        @Test
        void processCreatesTraceWithLlmOutput() {
            var prompt = buildPrompt("gpt-4", "user", "Hello");
            var datasetItem = buildDatasetItem(UUID.randomUUID(), Map.of());
            var experimentId = UUID.randomUUID();
            var datasetId = UUID.randomUUID();

            stubCommonMocks();
            when(chatCompletionService.create(any(ChatCompletionRequest.class), eq(WORKSPACE_ID)))
                    .thenReturn(buildLlmResponse("The answer is 42"));

            processor.process(buildMessage(prompt, datasetItem, experimentId, datasetId, null,
                    PROJECT_NAME, WORKSPACE_ID, USER_NAME)).block();

            var captor = ArgumentCaptor.forClass(Trace.class);
            verify(traceService).create(captor.capture());

            var output = captor.getValue().output();
            assertThat(output.get("output").asText()).isEqualTo("The answer is 42");
        }
    }

    @Nested
    @DisplayName("Span creation")
    class SpanCreation {

        @Test
        void processCreatesLlmSpanWithResolvedModelAndProvider() {
            var prompt = buildPrompt("gpt-4", "user", "Hello");
            var datasetItem = buildDatasetItem(UUID.randomUUID(), Map.of());
            var experimentId = UUID.randomUUID();
            var datasetId = UUID.randomUUID();

            stubCommonMocks();
            when(chatCompletionService.create(any(ChatCompletionRequest.class), eq(WORKSPACE_ID)))
                    .thenReturn(buildLlmResponse("response"));

            processor.process(buildMessage(prompt, datasetItem, experimentId, datasetId, null,
                    PROJECT_NAME, WORKSPACE_ID, USER_NAME)).block();

            var captor = ArgumentCaptor.forClass(Span.class);
            verify(spanService).create(captor.capture());

            var span = captor.getValue();
            assertThat(span.type()).isEqualTo(SpanType.llm);
            assertThat(span.model()).isEqualTo("gpt-4");
            assertThat(span.provider()).isEqualTo("openai");
            assertThat(span.name()).isEqualTo("chat_completion_create");
        }

        @Test
        void processCreatesSpanWithUsageData() {
            var prompt = buildPrompt("gpt-4", "user", "Hello");
            var datasetItem = buildDatasetItem(UUID.randomUUID(), Map.of());
            var experimentId = UUID.randomUUID();
            var datasetId = UUID.randomUUID();

            stubCommonMocks();
            when(chatCompletionService.create(any(ChatCompletionRequest.class), eq(WORKSPACE_ID)))
                    .thenReturn(buildLlmResponse("response"));

            processor.process(buildMessage(prompt, datasetItem, experimentId, datasetId, null,
                    PROJECT_NAME, WORKSPACE_ID, USER_NAME)).block();

            var captor = ArgumentCaptor.forClass(Span.class);
            verify(spanService).create(captor.capture());

            var usage = captor.getValue().usage();
            assertThat(usage).containsEntry("prompt_tokens", 10);
            assertThat(usage).containsEntry("completion_tokens", 20);
            assertThat(usage).containsEntry("total_tokens", 30);
        }

        @Test
        void processCreatesSpanLinkedToTrace() {
            var traceId = UUID.randomUUID();
            var spanId = UUID.randomUUID();
            var prompt = buildPrompt("gpt-4", "user", "Hello");
            var datasetItem = buildDatasetItem(UUID.randomUUID(), Map.of());
            var experimentId = UUID.randomUUID();
            var datasetId = UUID.randomUUID();

            when(idGenerator.generateId()).thenReturn(traceId, spanId);
            when(llmProviderFactory.getResolvedModelInfo("gpt-4"))
                    .thenReturn(new LlmProviderFactory.ResolvedModelInfo("gpt-4", "openai"));
            when(traceService.create(any(Trace.class))).thenReturn(Mono.just(traceId));
            when(spanService.create(any(Span.class))).thenReturn(Mono.just(spanId));
            when(experimentItemService.create(any())).thenReturn(Mono.empty());
            when(mustacheParser.renderUnescaped(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(chatCompletionService.create(any(ChatCompletionRequest.class), eq(WORKSPACE_ID)))
                    .thenReturn(buildLlmResponse("response"));

            processor.process(buildMessage(prompt, datasetItem, experimentId, datasetId, null,
                    PROJECT_NAME, WORKSPACE_ID, USER_NAME)).block();

            var captor = ArgumentCaptor.forClass(Span.class);
            verify(spanService).create(captor.capture());

            assertThat(captor.getValue().traceId()).isEqualTo(traceId);
        }
    }

    @Nested
    @DisplayName("Experiment item creation")
    class ExperimentItemCreation {

        @Test
        @SuppressWarnings("unchecked")
        void processCreatesExperimentItemLinkingExperimentDatasetItemAndTrace() {
            var traceId = UUID.randomUUID();
            var spanId = UUID.randomUUID();
            var experimentItemId = UUID.randomUUID();
            var datasetItemId = UUID.randomUUID();
            var experimentId = UUID.randomUUID();
            var datasetId = UUID.randomUUID();

            var prompt = buildPrompt("gpt-4", "user", "Hello");
            var datasetItem = buildDatasetItem(datasetItemId, Map.of());

            when(idGenerator.generateId()).thenReturn(traceId, spanId, experimentItemId);
            when(llmProviderFactory.getResolvedModelInfo("gpt-4"))
                    .thenReturn(new LlmProviderFactory.ResolvedModelInfo("gpt-4", "openai"));
            when(traceService.create(any(Trace.class))).thenReturn(Mono.just(traceId));
            when(spanService.create(any(Span.class))).thenReturn(Mono.just(spanId));
            when(experimentItemService.create(any())).thenReturn(Mono.empty());
            when(mustacheParser.renderUnescaped(any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
            when(chatCompletionService.create(any(ChatCompletionRequest.class), eq(WORKSPACE_ID)))
                    .thenReturn(buildLlmResponse("response"));

            processor.process(buildMessage(prompt, datasetItem, experimentId, datasetId, null,
                    PROJECT_NAME, WORKSPACE_ID, USER_NAME)).block();

            var captor = ArgumentCaptor.forClass(Set.class);
            verify(experimentItemService).create(captor.capture());

            var items = captor.getValue();
            assertThat(items).hasSize(1);
            var item = (ExperimentItem) items.iterator().next();
            assertThat(item.experimentId()).isEqualTo(experimentId);
            assertThat(item.datasetItemId()).isEqualTo(datasetItemId);
            assertThat(item.traceId()).isEqualTo(traceId);
        }
    }

    @Nested
    @DisplayName("LLM failure handling")
    class LlmFailureHandling {

        @Test
        void processStillCreatesTraceWhenLlmCallFails() {
            var prompt = buildPrompt("gpt-4", "user", "Hello");
            var datasetItem = buildDatasetItem(UUID.randomUUID(), Map.of());
            var experimentId = UUID.randomUUID();
            var datasetId = UUID.randomUUID();

            stubCommonMocks();
            when(chatCompletionService.create(any(ChatCompletionRequest.class), eq(WORKSPACE_ID)))
                    .thenThrow(new RuntimeException("LLM service unavailable"));

            processor.process(buildMessage(prompt, datasetItem, experimentId, datasetId, null,
                    PROJECT_NAME, WORKSPACE_ID, USER_NAME)).block();

            verify(traceService).create(any(Trace.class));
            verify(spanService).create(any(Span.class));
            verify(experimentItemService).create(any());
        }

        @Test
        void processCreatesTraceWithEmptyOutputOnLlmFailure() {
            var prompt = buildPrompt("gpt-4", "user", "Hello");
            var datasetItem = buildDatasetItem(UUID.randomUUID(), Map.of());
            var experimentId = UUID.randomUUID();
            var datasetId = UUID.randomUUID();

            stubCommonMocks();
            when(chatCompletionService.create(any(ChatCompletionRequest.class), eq(WORKSPACE_ID)))
                    .thenThrow(new RuntimeException("timeout"));

            processor.process(buildMessage(prompt, datasetItem, experimentId, datasetId, null,
                    PROJECT_NAME, WORKSPACE_ID, USER_NAME)).block();

            var captor = ArgumentCaptor.forClass(Trace.class);
            verify(traceService).create(captor.capture());

            var output = captor.getValue().output();
            assertThat(output.has("output")).isFalse();
        }
    }

    @Nested
    @DisplayName("Message role mapping")
    class MessageRoleMapping {

        @Test
        void processMapSystemRoleToSystemMessage() {
            var prompt = buildPrompt("gpt-4", "system", "You are a helpful assistant");
            var datasetItem = buildDatasetItem(UUID.randomUUID(), Map.of());
            var experimentId = UUID.randomUUID();
            var datasetId = UUID.randomUUID();

            stubCommonMocks();
            when(chatCompletionService.create(any(ChatCompletionRequest.class), eq(WORKSPACE_ID)))
                    .thenReturn(buildLlmResponse("response"));

            processor.process(buildMessage(prompt, datasetItem, experimentId, datasetId, null,
                    PROJECT_NAME, WORKSPACE_ID, USER_NAME)).block();

            var captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
            verify(chatCompletionService).create(captor.capture(), eq(WORKSPACE_ID));

            assertThat(captor.getValue().messages().getFirst()).isInstanceOf(SystemMessage.class);
        }

        @Test
        void processMapAssistantRoleToAssistantMessage() {
            var prompt = buildPrompt("gpt-4", "assistant", "I can help");
            var datasetItem = buildDatasetItem(UUID.randomUUID(), Map.of());
            var experimentId = UUID.randomUUID();
            var datasetId = UUID.randomUUID();

            stubCommonMocks();
            when(chatCompletionService.create(any(ChatCompletionRequest.class), eq(WORKSPACE_ID)))
                    .thenReturn(buildLlmResponse("response"));

            processor.process(buildMessage(prompt, datasetItem, experimentId, datasetId, null,
                    PROJECT_NAME, WORKSPACE_ID, USER_NAME)).block();

            var captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
            verify(chatCompletionService).create(captor.capture(), eq(WORKSPACE_ID));

            assertThat(captor.getValue().messages().getFirst()).isInstanceOf(AssistantMessage.class);
        }
    }

    @Nested
    @DisplayName("Config application")
    class ConfigApplication {

        @Test
        void processAppliesTemperatureAndMaxTokensConfigs() {
            var configs = Map.<String, JsonNode>of(
                    "temperature", new com.fasterxml.jackson.databind.node.DoubleNode(0.7),
                    "maxCompletionTokens", new IntNode(500),
                    "topP", new com.fasterxml.jackson.databind.node.DoubleNode(0.9),
                    "frequencyPenalty", new com.fasterxml.jackson.databind.node.DoubleNode(0.5),
                    "presencePenalty", new com.fasterxml.jackson.databind.node.DoubleNode(0.3));

            var prompt = ExperimentExecutionRequest.PromptVariant.builder()
                    .model("gpt-4")
                    .messages(List.of(
                            ExperimentExecutionRequest.PromptVariant.Message.builder()
                                    .role("user")
                                    .content(new TextNode("Hello"))
                                    .build()))
                    .configs(configs)
                    .build();

            var datasetItem = buildDatasetItem(UUID.randomUUID(), Map.of());
            var experimentId = UUID.randomUUID();
            var datasetId = UUID.randomUUID();

            stubCommonMocks();
            when(chatCompletionService.create(any(ChatCompletionRequest.class), eq(WORKSPACE_ID)))
                    .thenReturn(buildLlmResponse("response"));

            processor.process(buildMessage(prompt, datasetItem, experimentId, datasetId, null,
                    PROJECT_NAME, WORKSPACE_ID, USER_NAME)).block();

            var captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
            verify(chatCompletionService).create(captor.capture(), eq(WORKSPACE_ID));

            var request = captor.getValue();
            assertThat(request.temperature()).isEqualTo(0.7);
            assertThat(request.maxCompletionTokens()).isEqualTo(500);
            assertThat(request.topP()).isEqualTo(0.9);
            assertThat(request.frequencyPenalty()).isEqualTo(0.5);
            assertThat(request.presencePenalty()).isEqualTo(0.3);
        }

        @Test
        void processDoesNotSetStreamMode() {
            var prompt = buildPrompt("gpt-4", "user", "Hello");
            var datasetItem = buildDatasetItem(UUID.randomUUID(), Map.of());
            var experimentId = UUID.randomUUID();
            var datasetId = UUID.randomUUID();

            stubCommonMocks();
            when(chatCompletionService.create(any(ChatCompletionRequest.class), eq(WORKSPACE_ID)))
                    .thenReturn(buildLlmResponse("response"));

            processor.process(buildMessage(prompt, datasetItem, experimentId, datasetId, null,
                    PROJECT_NAME, WORKSPACE_ID, USER_NAME)).block();

            var captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
            verify(chatCompletionService).create(captor.capture(), eq(WORKSPACE_ID));

            assertThat(captor.getValue().stream()).isFalse();
        }
    }

    @Nested
    @DisplayName("Trace metadata without version hash")
    class TraceMetadataNoVersionHash {

        @Test
        void processOmitsVersionHashFromMetadataWhenNull() {
            var prompt = buildPrompt("gpt-4", "user", "Hello");
            var datasetItem = buildDatasetItem(UUID.randomUUID(), Map.of());
            var experimentId = UUID.randomUUID();
            var datasetId = UUID.randomUUID();

            stubCommonMocks();
            when(chatCompletionService.create(any(ChatCompletionRequest.class), eq(WORKSPACE_ID)))
                    .thenReturn(buildLlmResponse("response"));

            processor.process(buildMessage(prompt, datasetItem, experimentId, datasetId, null,
                    PROJECT_NAME, WORKSPACE_ID, USER_NAME)).block();

            var captor = ArgumentCaptor.forClass(Trace.class);
            verify(traceService).create(captor.capture());

            var metadata = captor.getValue().metadata();
            assertThat(metadata.has("eval_suite_dataset_version_hash")).isFalse();
        }
    }
}
