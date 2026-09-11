package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetExpansion;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.DatasetType;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.Visibility;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.model.openai.internal.chat.AssistantMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionChoice;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import jakarta.inject.Provider;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("DatasetExpansionService Test")
class DatasetExpansionServiceTest {

    @Mock
    private ChatCompletionService chatCompletionService;
    @Mock
    private LlmProviderFactory llmProviderFactory;
    @Mock
    private DatasetItemService datasetItemService;
    @Mock
    private DatasetService datasetService;
    @Mock
    private Provider<RequestContext> requestContextProvider;
    @Mock
    private RequestContext requestContext;
    @Mock
    private IdGenerator idGenerator;

    private final ObjectMapper objectMapper = JsonUtils.getMapper();

    private DatasetExpansionService service;

    private static final UUID DATASET_ID = UUID.randomUUID();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();

    @BeforeEach
    void setUp() {
        service = new DatasetExpansionService(
                chatCompletionService, llmProviderFactory, datasetItemService,
                datasetService, requestContextProvider, objectMapper, idGenerator);
    }

    @Nested
    @DisplayName("maxCompletionTokens Resolution")
    class MaxCompletionTokensResolution {

        @BeforeEach
        void setUp() {
            setupRequestContext();
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
        }

        @ParameterizedTest(name = "User-provided maxCompletionTokens={0} is used for provider {1}")
        @MethodSource("userProvidedTokensTestCases")
        @DisplayName("User-provided maxCompletionTokens is always used regardless of provider")
        void userProvidedMaxCompletionTokens(int userTokens, LlmProvider provider) {
            setupMocksForExpansion(DatasetType.DATASET);
            setupLlmResponse();

            var request = DatasetExpansion.builder()
                    .model("test-model")
                    .sampleCount(1)
                    .maxCompletionTokens(userTokens)
                    .build();

            service.expandDataset(DATASET_ID, request);

            var captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
            verify(chatCompletionService).create(captor.capture(), eq(WORKSPACE_ID));
            assertThat(captor.getValue().maxCompletionTokens()).isEqualTo(userTokens);
        }

        static Stream<Arguments> userProvidedTokensTestCases() {
            return Stream.of(
                    Arguments.of(8000, LlmProvider.ANTHROPIC),
                    Arguments.of(8000, LlmProvider.OPEN_AI),
                    Arguments.of(2000, LlmProvider.GEMINI));
        }

        @Test
        @DisplayName("Anthropic model gets default maxCompletionTokens when not provided by user")
        void anthropicDefaultMaxCompletionTokens() {
            setupMocksForExpansion(DatasetType.DATASET);
            when(llmProviderFactory.getLlmProvider("claude-sonnet-4-20250514")).thenReturn(LlmProvider.ANTHROPIC);
            setupLlmResponse();

            var request = DatasetExpansion.builder()
                    .model("claude-sonnet-4-20250514")
                    .sampleCount(1)
                    .build();

            service.expandDataset(DATASET_ID, request);

            var captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
            verify(chatCompletionService).create(captor.capture(), eq(WORKSPACE_ID));
            assertThat(captor.getValue().maxCompletionTokens()).isEqualTo(4000);
        }

        @ParameterizedTest(name = "Provider {0} gets no maxCompletionTokens when not provided by user")
        @MethodSource("nonAnthropicProviders")
        @DisplayName("Non-Anthropic providers get no maxCompletionTokens when not provided by user")
        void nonAnthropicNoMaxCompletionTokens(LlmProvider provider) {
            setupMocksForExpansion(DatasetType.DATASET);
            when(llmProviderFactory.getLlmProvider("test-model")).thenReturn(provider);
            setupLlmResponse();

            var request = DatasetExpansion.builder()
                    .model("test-model")
                    .sampleCount(1)
                    .build();

            service.expandDataset(DATASET_ID, request);

            var captor = ArgumentCaptor.forClass(ChatCompletionRequest.class);
            verify(chatCompletionService).create(captor.capture(), eq(WORKSPACE_ID));
            assertThat(captor.getValue().maxCompletionTokens()).isNull();
        }

        static Stream<Arguments> nonAnthropicProviders() {
            return Stream.of(
                    Arguments.of(LlmProvider.OPEN_AI),
                    Arguments.of(LlmProvider.GEMINI),
                    Arguments.of(LlmProvider.OPEN_ROUTER));
        }
    }

    @Nested
    @DisplayName("Dataset Item Building")
    class DatasetItemBuilding {

        @BeforeEach
        void setUp() {
            setupRequestContext();
            when(idGenerator.generateId()).thenReturn(UUID.randomUUID());
        }

        @Test
        @DisplayName("Regular dataset items include _generated and _generation_model metadata")
        void regularDatasetIncludesMetadata() {
            setupMocksForExpansion(DatasetType.DATASET);
            when(llmProviderFactory.getLlmProvider("gpt-4")).thenReturn(LlmProvider.OPEN_AI);
            setupLlmResponse();

            var request = DatasetExpansion.builder()
                    .model("gpt-4")
                    .sampleCount(1)
                    .build();

            var result = service.expandDataset(DATASET_ID, request);

            var item = result.generatedSamples().getFirst();
            assertThat(item.data()).containsKey("_generated");
            assertThat(item.data().get("_generated").asBoolean()).isTrue();
            assertThat(item.data()).containsKey("_generation_model");
            assertThat(item.data().get("_generation_model").asText()).isEqualTo("gpt-4");
        }

        @Test
        @DisplayName("Test suite items do not include _generated and _generation_model metadata")
        void testSuiteExcludesMetadata() {
            setupMocksForExpansion(DatasetType.TEST_SUITE);
            when(llmProviderFactory.getLlmProvider("gpt-4")).thenReturn(LlmProvider.OPEN_AI);
            setupLlmResponse();

            var request = DatasetExpansion.builder()
                    .model("gpt-4")
                    .sampleCount(1)
                    .build();

            var result = service.expandDataset(DATASET_ID, request);

            var item = result.generatedSamples().getFirst();
            assertThat(item.data()).doesNotContainKey("_generated");
            assertThat(item.data()).doesNotContainKey("_generation_model");
        }

        @Test
        @DisplayName("Generated items have correct datasetId and source")
        void generatedItemsHaveCorrectFields() {
            setupMocksForExpansion(DatasetType.DATASET);
            when(llmProviderFactory.getLlmProvider("gpt-4")).thenReturn(LlmProvider.OPEN_AI);
            setupLlmResponse();

            var request = DatasetExpansion.builder()
                    .model("gpt-4")
                    .sampleCount(1)
                    .build();

            var result = service.expandDataset(DATASET_ID, request);

            var item = result.generatedSamples().getFirst();
            assertThat(item.datasetId()).isEqualTo(DATASET_ID);
            assertThat(item.source()).isEqualTo(DatasetItemSource.MANUAL);
            assertThat(item.id()).isNotNull();
        }
    }

    @Nested
    @DisplayName("Error Handling")
    class ErrorHandling {

        @BeforeEach
        void setUp() {
            setupRequestContext();
        }

        @Test
        @DisplayName("Empty dataset throws BadRequestException")
        void emptyDatasetThrowsBadRequest() {
            var emptyPage = new DatasetItem.DatasetItemPage(List.of(), 0, 0, 0, Set.of(), List.of());
            when(datasetItemService.getItems(eq(1), eq(10), any()))
                    .thenReturn(Mono.just(emptyPage));

            var request = DatasetExpansion.builder()
                    .model("gpt-4")
                    .sampleCount(1)
                    .build();

            assertThatThrownBy(() -> service.expandDataset(DATASET_ID, request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Cannot expand empty dataset");
        }

        @Test
        @DisplayName("Dataset not found throws BadRequestException")
        void datasetNotFoundThrowsBadRequest() {
            setupExistingItems();
            when(datasetService.getById(DATASET_ID, WORKSPACE_ID)).thenReturn(Optional.empty());

            var request = DatasetExpansion.builder()
                    .model("gpt-4")
                    .sampleCount(1)
                    .build();

            assertThatThrownBy(() -> service.expandDataset(DATASET_ID, request))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Dataset not found");
        }

        @Test
        @DisplayName("LLM ClientErrorException is re-thrown as-is")
        void llmClientErrorRethrown() {
            setupMocksForExpansion(DatasetType.DATASET);
            when(llmProviderFactory.getLlmProvider("gpt-4")).thenReturn(LlmProvider.OPEN_AI);
            when(chatCompletionService.create(any(), eq(WORKSPACE_ID)))
                    .thenThrow(new ClientErrorException("API key not configured", Response.Status.UNAUTHORIZED));

            var request = DatasetExpansion.builder()
                    .model("gpt-4")
                    .sampleCount(1)
                    .build();

            assertThatThrownBy(() -> service.expandDataset(DATASET_ID, request))
                    .isInstanceOf(ClientErrorException.class)
                    .hasMessageContaining("API key not configured");
        }

        @Test
        @DisplayName("Unexpected exception is wrapped as InternalServerErrorException")
        void unexpectedExceptionWrappedAs500() {
            setupMocksForExpansion(DatasetType.DATASET);
            when(llmProviderFactory.getLlmProvider("gpt-4")).thenReturn(LlmProvider.OPEN_AI);
            when(chatCompletionService.create(any(), eq(WORKSPACE_ID)))
                    .thenThrow(new RuntimeException("unexpected"));

            var request = DatasetExpansion.builder()
                    .model("gpt-4")
                    .sampleCount(1)
                    .build();

            assertThatThrownBy(() -> service.expandDataset(DATASET_ID, request))
                    .isInstanceOf(InternalServerErrorException.class)
                    .hasMessageContaining("Failed to generate synthetic samples");
        }
    }

    private void setupRequestContext() {
        when(requestContextProvider.get()).thenReturn(requestContext);
        when(requestContext.getWorkspaceId()).thenReturn(WORKSPACE_ID);
        when(requestContext.getUserName()).thenReturn("test-user");
        when(requestContext.getWorkspaceName()).thenReturn("test-workspace");
        when(requestContext.getVisibility()).thenReturn(Visibility.PRIVATE);
    }

    private void setupMocksForExpansion(DatasetType datasetType) {
        setupExistingItems();
        var dataset = Dataset.builder()
                .id(DATASET_ID)
                .name("test-dataset")
                .type(datasetType)
                .build();
        when(datasetService.getById(DATASET_ID, WORKSPACE_ID)).thenReturn(Optional.of(dataset));
    }

    private void setupExistingItems() {
        var existingItem = DatasetItem.builder()
                .id(UUID.randomUUID())
                .datasetId(DATASET_ID)
                .data(Map.of("input", objectMapper.valueToTree("test input")))
                .source(DatasetItemSource.MANUAL)
                .build();
        var page = new DatasetItem.DatasetItemPage(List.of(existingItem), 1, 10, 1, Set.of(), List.of());
        when(datasetItemService.getItems(eq(1), eq(10), any()))
                .thenReturn(Mono.just(page));
    }

    private void setupLlmResponse() {
        var response = ChatCompletionResponse.builder()
                .choices(List.of(ChatCompletionChoice.builder()
                        .index(0)
                        .message(AssistantMessage.builder()
                                .content("[{\"input\": \"generated input\"}]")
                                .build())
                        .build()))
                .build();
        when(chatCompletionService.create(any(), eq(WORKSPACE_ID))).thenReturn(response);
    }
}
