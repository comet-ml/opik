package com.comet.opik.domain.llm;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.ChunkedOutputHandlers;
import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.openai.errors.OpenAIServiceException;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.AuthenticationException;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.RateLimitException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.exception.UnsupportedFeatureException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.WebApplicationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Chat Completion Service Test")
class ChatCompletionServiceTest {

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @Mock
    private LlmProviderClientConfig llmProviderClientConfig;

    @Mock
    private LlmProviderFactory llmProviderFactory;

    @Mock
    private LlmProviderService llmProviderService;

    @Mock
    private ChatModel chatModel;

    private ChatCompletionService chatCompletionService;

    @BeforeEach
    void setUp() {
        // Setup default config values to disable retries for tests
        when(llmProviderClientConfig.getMaxAttempts()).thenReturn(1);
        when(llmProviderClientConfig.getDelayMillis()).thenReturn(100);
        when(llmProviderClientConfig.getJitterScale()).thenReturn(null);
        when(llmProviderClientConfig.getBackoffExp()).thenReturn(null);

        chatCompletionService = new ChatCompletionService(llmProviderClientConfig, llmProviderFactory);
    }

    @Nested
    @DisplayName("Create Method Error Handling:")
    class CreateMethodErrorHandling {

        private static Stream<Arguments> connectionExceptionProvider() {
            return Stream.of(
                    Arguments.of(
                            "ConnectException with 'Connection refused'",
                            new ConnectException("Connection refused"),
                            "Service is unreachable"),
                    Arguments.of(
                            "ConnectException with custom message",
                            new ConnectException("Connection to host:8080 failed"),
                            "Service is unreachable"),
                    Arguments.of(
                            "ClosedChannelException",
                            new ClosedChannelException(),
                            "Service is unreachable"));
        }

        @ParameterizedTest(name = "when {0}, then throw InternalServerErrorException with user-friendly message")
        @MethodSource("connectionExceptionProvider")
        @DisplayName("Connection exceptions should produce user-friendly error messages")
        void create__whenConnectionException__thenThrowWithUserFriendlyMessage(
                String testName, Exception causeException, String expectedMessagePart) {
            // Given
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";
            var runtimeException = new RuntimeException("Connection error", causeException);

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            when(llmProviderService.generate(any(), anyString())).thenThrow(runtimeException);
            when(llmProviderService.getLlmProviderError(any())).thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> chatCompletionService.create(request, workspaceId))
                    .isInstanceOf(InternalServerErrorException.class)
                    .hasMessageContaining("Unexpected error calling LLM provider")
                    .hasMessageContaining(expectedMessagePart)
                    .hasCauseInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("when generic RuntimeException occurs, then throw InternalServerErrorException with exception message")
        void create__whenGenericRuntimeException__thenThrowWithExceptionMessage() {
            // Given
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";
            var errorMessage = "Custom error message from provider";
            var runtimeException = new RuntimeException(errorMessage);

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            when(llmProviderService.generate(any(), anyString())).thenThrow(runtimeException);
            when(llmProviderService.getLlmProviderError(any())).thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> chatCompletionService.create(request, workspaceId))
                    .isInstanceOf(InternalServerErrorException.class)
                    .hasMessageContaining("Unexpected error calling LLM provider")
                    .hasMessageContaining(errorMessage)
                    .hasCauseInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("when RuntimeException with no message occurs, then throw InternalServerErrorException with exception class name")
        void create__whenRuntimeExceptionWithNoMessage__thenThrowWithClassName() {
            // Given
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";
            var runtimeException = new RuntimeException((String) null);

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            when(llmProviderService.generate(any(), anyString())).thenThrow(runtimeException);
            when(llmProviderService.getLlmProviderError(any())).thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> chatCompletionService.create(request, workspaceId))
                    .isInstanceOf(InternalServerErrorException.class)
                    .hasMessageContaining("Unexpected error calling LLM provider")
                    .hasMessageContaining("RuntimeException")
                    .hasCauseInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("when nested exception occurs, then extract root cause message")
        void create__whenNestedException__thenExtractRootCauseMessage() {
            // Given
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";
            var rootCauseMessage = "Root cause error message";
            var rootCause = new IllegalStateException(rootCauseMessage);
            var middleException = new RuntimeException("Middle exception", rootCause);
            var topException = new RuntimeException("Top exception", middleException);

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            when(llmProviderService.generate(any(), anyString())).thenThrow(topException);
            when(llmProviderService.getLlmProviderError(any())).thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> chatCompletionService.create(request, workspaceId))
                    .isInstanceOf(InternalServerErrorException.class)
                    .hasMessageContaining("Unexpected error calling LLM provider")
                    .hasMessageContaining(rootCauseMessage)
                    .hasCauseInstanceOf(RuntimeException.class);
        }

        @Test
        @DisplayName("when provider returns error message, then propagate provider error")
        void create__whenProviderReturnsError__thenPropagateProviderError() {
            // Given
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";
            var providerErrorMessage = "Invalid API key";
            var providerError = new ErrorMessage(401, providerErrorMessage);
            var runtimeException = new RuntimeException("Provider error");

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            when(llmProviderService.generate(any(), anyString())).thenThrow(runtimeException);
            when(llmProviderService.getLlmProviderError(any())).thenReturn(Optional.of(providerError));

            // When & Then
            assertThatThrownBy(() -> chatCompletionService.create(request, workspaceId))
                    .hasMessageContaining(providerErrorMessage);
        }

        @Test
        @DisplayName("when successful, then return chat completion response")
        void create__whenSuccessful__thenReturnResponse() {
            // Given
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";
            var expectedResponse = podamFactory.manufacturePojo(ChatCompletionResponse.class);

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            when(llmProviderService.generate(any(), anyString())).thenReturn(expectedResponse);

            // When
            var actualResponse = chatCompletionService.create(request, workspaceId);

            // Then
            assertThat(actualResponse).isEqualTo(expectedResponse);
        }
    }

    @Nested
    @DisplayName("Unsupported Feature Handling:")
    class UnsupportedFeatureHandling {

        private static final String UNSUPPORTED_FEATURE_MESSAGE = "ToolChoice.REQUIRED is not supported yet by this model provider";

        private static Stream<Arguments> unsupportedFeatureExceptionCases() {
            return Stream.of(
                    Arguments.of(
                            "thrown directly",
                            new UnsupportedFeatureException(UNSUPPORTED_FEATURE_MESSAGE)),
                    Arguments.of(
                            "wrapped in a RuntimeException",
                            new RuntimeException("Retry wrapper",
                                    new UnsupportedFeatureException(UNSUPPORTED_FEATURE_MESSAGE))));
        }

        @ParameterizedTest(name = "when UnsupportedFeatureException is {0}, then throw BadRequestException")
        @MethodSource("unsupportedFeatureExceptionCases")
        @DisplayName("create should map unsupported features to 400, not 500")
        void create__whenUnsupportedFeatureException__thenThrowBadRequest(
                String testName, RuntimeException runtimeException) {
            // Given
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            when(llmProviderService.generate(any(), anyString())).thenThrow(runtimeException);

            // When
            var thrown = catchThrowable(() -> chatCompletionService.create(request, workspaceId));

            // Then
            assertThat(thrown)
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Unsupported feature for the selected LLM provider")
                    .hasMessageContaining(UNSUPPORTED_FEATURE_MESSAGE);
            assertThat(((BadRequestException) thrown).getResponse().getStatus()).isEqualTo(400);
        }

        @ParameterizedTest(name = "when UnsupportedFeatureException is {0}, then throw BadRequestException")
        @MethodSource("unsupportedFeatureExceptionCases")
        @DisplayName("scoreTrace should map unsupported features to 400, not 500")
        void scoreTrace__whenUnsupportedFeatureException__thenThrowBadRequest(
                String testName, RuntimeException runtimeException) {
            // Given
            var chatRequest = ChatRequest.builder().messages(UserMessage.from("score this")).build();
            var modelParameters = LlmAsJudgeModelParameters.builder()
                    .name("vertex_ai/gemini-3.1-flash-lite")
                    .temperature(0.0)
                    .build();
            var workspaceId = "test-workspace-id";

            when(llmProviderFactory.getLanguageModel(anyString(), any())).thenReturn(chatModel);
            when(chatModel.chat(any(ChatRequest.class))).thenThrow(runtimeException);

            // When
            var thrown = catchThrowable(
                    () -> chatCompletionService.scoreTrace(chatRequest, modelParameters, workspaceId));

            // Then
            assertThat(thrown)
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Unsupported feature for the selected LLM provider")
                    .hasMessageContaining(UNSUPPORTED_FEATURE_MESSAGE);
            assertThat(((BadRequestException) thrown).getResponse().getStatus()).isEqualTo(400);
        }

        @Test
        @DisplayName("scoreTrace should not consult the provider error mapper for unsupported features")
        void scoreTrace__whenUnsupportedFeatureException__thenSkipProviderErrorLookup() {
            // Given
            var chatRequest = ChatRequest.builder().messages(UserMessage.from("score this")).build();
            var modelParameters = LlmAsJudgeModelParameters.builder()
                    .name("vertex_ai/gemini-3.1-flash-lite")
                    .temperature(0.0)
                    .build();
            var workspaceId = "test-workspace-id";

            when(llmProviderFactory.getLanguageModel(anyString(), any())).thenReturn(chatModel);
            when(chatModel.chat(any(ChatRequest.class)))
                    .thenThrow(new UnsupportedFeatureException(UNSUPPORTED_FEATURE_MESSAGE));

            // When & Then
            assertThatThrownBy(() -> chatCompletionService.scoreTrace(chatRequest, modelParameters, workspaceId))
                    .isInstanceOf(BadRequestException.class);

            // The provider was never reached, so there is no provider error to map.
            verify(llmProviderFactory, never()).getService(anyString(), anyString());
            verify(llmProviderService, never()).getLlmProviderError(any());
        }

        @Test
        @DisplayName("the 400 response body carries the unsupported-feature message")
        void create__whenUnsupportedFeatureException__thenResponseBodyCarriesMessage() {
            // Given
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            when(llmProviderService.generate(any(), anyString()))
                    .thenThrow(new UnsupportedFeatureException(UNSUPPORTED_FEATURE_MESSAGE));

            // When
            var thrown = (BadRequestException) catchThrowable(
                    () -> chatCompletionService.create(request, workspaceId));

            // Then — Jersey renders the Response, so the entity is what the caller actually receives
            var response = thrown.getResponse();
            assertThat(response.getStatus()).isEqualTo(400);
            assertThat(response.getEntity()).isInstanceOf(ErrorMessage.class);

            var errorMessage = (ErrorMessage) response.getEntity();
            assertThat(errorMessage.getCode()).isEqualTo(400);
            assertThat(errorMessage.getMessage())
                    .isEqualTo("Unsupported feature for the selected LLM provider: " + UNSUPPORTED_FEATURE_MESSAGE);
        }

        @Test
        @DisplayName("only the unsupported-feature message is exposed, not deeper causes")
        void create__whenUnsupportedFeatureWrapsAnotherCause__thenDeeperDetailNotExposed() {
            // Given — a deeper root cause that must not reach the client
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";
            var wrapped = new RuntimeException("internal wiring detail",
                    new UnsupportedFeatureException(UNSUPPORTED_FEATURE_MESSAGE));

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            when(llmProviderService.generate(any(), anyString())).thenThrow(wrapped);

            // When
            var thrown = (BadRequestException) catchThrowable(
                    () -> chatCompletionService.create(request, workspaceId));

            // Then
            var errorMessage = (ErrorMessage) thrown.getResponse().getEntity();
            assertThat(errorMessage.getMessage())
                    .isEqualTo("Unsupported feature for the selected LLM provider: " + UNSUPPORTED_FEATURE_MESSAGE)
                    .doesNotContain("internal wiring detail");
        }

        @Test
        @DisplayName("unsupported features must not consume the provider retry budget")
        void create__whenUnsupportedFeatureException__thenNotRetried() {
            // Given — a policy that would retry, unlike the single-attempt default used by the other tests
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";

            when(llmProviderClientConfig.getMaxAttempts()).thenReturn(3);
            var retryingService = new ChatCompletionService(llmProviderClientConfig, llmProviderFactory);

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            when(llmProviderService.generate(any(), anyString()))
                    .thenThrow(new UnsupportedFeatureException(UNSUPPORTED_FEATURE_MESSAGE));

            // When & Then
            assertThatThrownBy(() -> retryingService.create(request, workspaceId))
                    .isInstanceOf(BadRequestException.class);

            // UnsupportedFeatureException extends LangChain4jException, not NonRetriableException, so without the
            // fail-fast wrapper langchain4j's RetryPolicy would retry a call that can never succeed.
            verify(llmProviderService, times(1)).generate(any(), anyString());
        }

        @Test
        @DisplayName("transient failures must still be retried")
        void create__whenTransientException__thenStillRetried() {
            // Given
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";

            when(llmProviderClientConfig.getMaxAttempts()).thenReturn(3);
            var retryingService = new ChatCompletionService(llmProviderClientConfig, llmProviderFactory);

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            when(llmProviderService.generate(any(), anyString())).thenThrow(new RuntimeException("transient"));
            when(llmProviderService.getLlmProviderError(any())).thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> retryingService.create(request, workspaceId))
                    .isInstanceOf(InternalServerErrorException.class);

            verify(llmProviderService, atLeast(2)).generate(any(), anyString());
        }

        @Test
        @DisplayName("streaming gives unsupported features precedence over a provider error envelope")
        void createAndStreamResponse__whenUnsupportedFeatureAndProviderError__thenReportBadRequest() {
            // Given — the provider mapper would happily classify this throwable, but the capability failure wins
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";
            var handlers = mock(ChunkedOutputHandlers.class);
            var unsupported = new UnsupportedFeatureException(UNSUPPORTED_FEATURE_MESSAGE);

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            lenient().when(llmProviderService.getLlmProviderError(any()))
                    .thenReturn(Optional.of(new ErrorMessage(503, "provider says unavailable")));
            doAnswer(invocation -> {
                Consumer<Throwable> errorHandler = invocation.getArgument(4);
                errorHandler.accept(unsupported);
                return null;
            }).when(llmProviderService).generateStream(any(), anyString(), any(), any(), any());

            // When
            chatCompletionService.createAndStreamResponse(request, workspaceId, handlers);

            // Then
            var errorCaptor = ArgumentCaptor.forClass(ErrorMessage.class);
            verify(handlers).handleError(errorCaptor.capture());
            assertThat(errorCaptor.getValue().getCode()).isEqualTo(400);
            assertThat(errorCaptor.getValue().getMessage())
                    .contains("Unsupported feature for the selected LLM provider")
                    .contains(UNSUPPORTED_FEATURE_MESSAGE);
        }

        @Test
        @DisplayName("a synchronous BadRequestException still escapes as a real HTTP status")
        void createAndStreamResponse__whenGenerateStreamThrowsBadRequest__thenPropagates() {
            // Given — LlmProviderAnthropic.generateStream calls validateRequest inline and throws this before any
            // provider I/O. Nothing upstream validates messages: ChatCompletionsResource only checks the model, and
            // ChatCompletionRequest is langchain4j's class, so @Valid contributes no constraints. Swallowing it into
            // the stream would turn a malformed request into an HTTP 200 for callers branching on status.
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";
            var handlers = mock(ChunkedOutputHandlers.class);

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            doThrow(new BadRequestException(ChatCompletionService.ERROR_EMPTY_MESSAGES))
                    .when(llmProviderService).generateStream(any(), anyString(), any(), any(), any());

            // When & Then — createAndStreamResponse runs before Response.ok() is built, so nothing is committed yet
            // and this surfaces as a genuine 400
            assertThatThrownBy(() -> chatCompletionService.createAndStreamResponse(request, workspaceId, handlers))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining(ChatCompletionService.ERROR_EMPTY_MESSAGES);

            verify(handlers, never()).handleError(any());
        }

        @Test
        @DisplayName("a synchronously thrown UnsupportedFeatureException is delivered in-stream as a code-400 ErrorMessage, leaving the HTTP response 200")
        void createAndStreamResponse__whenGenerateStreamThrowsUnsupportedFeature__thenErrorStreamed() {
            // Given — OpenAiResponses and friends run inline, so this escapes generateStream instead of reaching the
            // error callback the way VertexAI and Gemini do
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";
            var handlers = mock(ChunkedOutputHandlers.class);

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            doThrow(new UnsupportedFeatureException(UNSUPPORTED_FEATURE_MESSAGE))
                    .when(llmProviderService).generateStream(any(), anyString(), any(), any(), any());

            // When — must not escape, so the resource still returns 200 with the stream
            chatCompletionService.createAndStreamResponse(request, workspaceId, handlers);

            // Then
            var errorCaptor = ArgumentCaptor.forClass(ErrorMessage.class);
            verify(handlers).handleError(errorCaptor.capture());
            assertThat(errorCaptor.getValue().getCode()).isEqualTo(400);
            assertThat(errorCaptor.getValue().getMessage())
                    .isEqualTo("Unsupported feature for the selected LLM provider: " + UNSUPPORTED_FEATURE_MESSAGE);
        }

        @Test
        @DisplayName("unrelated synchronous failures still propagate to the resource layer")
        void createAndStreamResponse__whenGenerateStreamThrowsUnrelated__thenPropagates() {
            // Given
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";
            var handlers = mock(ChunkedOutputHandlers.class);

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            doThrow(new IllegalStateException("connection pool exhausted"))
                    .when(llmProviderService).generateStream(any(), anyString(), any(), any(), any());

            // When & Then — not a client error, so it keeps its existing behaviour rather than becoming a 200
            assertThatThrownBy(() -> chatCompletionService.createAndStreamResponse(request, workspaceId, handlers))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("connection pool exhausted");

            verify(handlers, never()).handleError(any());
        }

        @Test
        @DisplayName("unrelated runtime exceptions should still produce a 500")
        void create__whenUnrelatedException__thenStillThrowInternalServerError() {
            // Given
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            when(llmProviderService.generate(any(), anyString())).thenThrow(new RuntimeException("boom"));
            when(llmProviderService.getLlmProviderError(any())).thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> chatCompletionService.create(request, workspaceId))
                    .isInstanceOf(InternalServerErrorException.class)
                    .hasMessageContaining("Unexpected error calling LLM provider");
        }
    }

    @Nested
    @DisplayName("Provider Reported Status Fallback:")
    class ProviderReportedStatusFallback {

        /**
         * Shapes taken from production: 163 upstream-proxy 503s, 72 Anthropic billing rejections and 53 Cloudflare
         * 1015s were all reported as 500s because {@code getLlmProviderError} could not parse them — the two
         * plain-text bodies carry no JSON envelope, and the Anthropic typed exception arrives nested rather than bare.
         * The last two rows are the invariants that keep the fallback honest: an {@code HttpException} anywhere in the
         * chain outranks the typed exception wrapping it (otherwise langchain4j's
         * {@code InternalServerException(HttpException(503))} would flatten to 500), and a failure that never reached
         * HTTP carries no status to recover, so it stays a 500.
         *
         * <p>The same rows drive {@code create}, {@code scoreTrace} and the streaming handler, because the three paths
         * are only worth having if they agree on the status.
         */
        private static Stream<Arguments> providerStatusProvider() {
            return Stream.of(
                    Arguments.of(
                            "upstream proxy 503 with a plain-text body",
                            new RuntimeException(new HttpException(503, "[PROXY] service temporarily unavailable")),
                            503,
                            "[PROXY] service temporarily unavailable"),
                    Arguments.of(
                            "Anthropic billing rejection nested under a retry wrapper",
                            new NonRetriableException(new InvalidRequestException(
                                    "{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"Your credit balance is too low to access the Anthropic API.\"}}")),
                            400,
                            "credit balance is too low"),
                    Arguments.of(
                            "Cloudflare rate limit with a plain-text body",
                            new RuntimeException(new HttpException(429, "error code: 1015")),
                            429,
                            "error code: 1015"),
                    Arguments.of(
                            "bare RateLimitException with no HttpException in the chain",
                            new RateLimitException("slow down"),
                            429,
                            "slow down"),
                    Arguments.of(
                            "bare AuthenticationException",
                            new AuthenticationException("bad key"),
                            401,
                            "bad key"),
                    Arguments.of(
                            "bare TimeoutException",
                            new TimeoutException("provider timed out"),
                            408,
                            "provider timed out"),
                    Arguments.of(
                            "typed exception wrapping an HttpException",
                            new InternalServerException(new HttpException(503, "upstream is down")),
                            503,
                            "upstream is down"),
                    Arguments.of(
                            "failure that never reached HTTP",
                            new RuntimeException("Connection error", new ConnectException("Connection refused")),
                            500,
                            "Service is unreachable"),
                    Arguments.of(
                            "status outside the error families",
                            new RuntimeException(new HttpException(302, "moved")),
                            500,
                            "moved"),
                    Arguments.of(
                            "status outside the valid HTTP range",
                            new RuntimeException(new HttpException(0, "no response")),
                            500,
                            "no response"));
        }

        @ParameterizedTest(name = "create: when {0}, then report status {2}")
        @MethodSource("providerStatusProvider")
        @DisplayName("An unparsed provider error keeps the status langchain4j determined")
        void create__whenProviderErrorUnparsed__thenReportProviderStatus(
                String testName, RuntimeException providerFailure, int expectedStatus, String expectedMessagePart) {
            // Given
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            when(llmProviderService.generate(any(), anyString())).thenThrow(providerFailure);
            when(llmProviderService.getLlmProviderError(any())).thenReturn(Optional.empty());

            // When
            var thrown = catchThrowable(() -> chatCompletionService.create(request, workspaceId));

            // Then
            assertThat(thrown)
                    .isInstanceOf(WebApplicationException.class)
                    .hasMessageContaining(expectedMessagePart);
            assertThat(((WebApplicationException) thrown).getResponse().getStatus()).isEqualTo(expectedStatus);
        }

        /**
         * The statuses the subscriber must retire, spelled out as a literal and used both to partition the
         * shared rows below and to assert every retryability outcome. Deliberately NOT
         * {@code HttpStatusRetryability.isPermanent}: a test that derives its expectation from the predicate
         * under test agrees with that predicate even when it is wrong, so production and tests would regress
         * together silently. The predicate's own mapping is pinned separately, also with literals, in
         * {@code HttpStatusRetryabilityTest}.
         */
        /**
         * Exact attempt count for the retry-budget tests, which set {@code getMaxAttempts()} to 3. That value is
         * passed to langchain4j as {@code RetryPolicy.maxRetries}, and its contract is "the action can be executed
         * up to maxRetries + 1 times" — so a fully-consumed budget is 4 calls, not 3. Asserted exactly rather than
         * with {@code atLeast}, so an implementation that retries fewer times fails.
         */
        private static final int EXPECTED_ATTEMPTS_AT_MAX_RETRIES_3 = 4;

        private static final Set<Integer> PERMANENT_STATUSES = Set.of(400, 401, 403, 404, 413, 422, 499);

        /** The shared rows whose status is permanent, so their scoreTrace case needs no branch. */
        private static Stream<Arguments> permanentProviderStatuses() {
            return providerStatusProvider().filter(row -> PERMANENT_STATUSES.contains(row.get()[2]));
        }

        /** The complement, kept separate for the same reason. */
        private static Stream<Arguments> transientProviderStatuses() {
            return providerStatusProvider().filter(row -> !PERMANENT_STATUSES.contains(row.get()[2]));
        }

        /**
         * OPIK-8240. The redaction-limit incident: Uber's gateway rejects an oversized scoring request with
         * a plain-text 400 that getLlmProviderError cannot parse (no JSON envelope, so no '{' for
         * extractErrorJson to find). scoreTrace used to answer every unparsed provider error with a blanket
         * 500, which lands outside BaseRedisSubscriber.NON_RETRYABLE_EXCEPTIONS - so a request that could
         * never succeed was replayed maxRetries times, once per pendingMessageDuration, before being
         * dropped.
         *
         * <p>The status now comes off the HttpException in the cause chain (findProviderHttpStatus) rather
         * than the unparseable body, and a permanent 4xx becomes a ClientErrorException so the subscriber
         * retires it on the first delivery.
         */
        @ParameterizedTest(name = "scoreTrace: permanent {0} -> non-retryable ClientErrorException")
        @CsvSource({
                "400, Bad Request",
                "401, Unauthorized",
                "403, Forbidden",
                "404, Not Found",
                "413, Payload Too Large",
                "422, Unprocessable Entity",
        })
        @DisplayName("A permanent provider 4xx becomes non-retryable, so the evaluation is dropped not replayed")
        void scoreTrace__whenPermanentClientError__thenNonRetryable(int status, String label) {
            // Plain-text body, exactly like the real gateway rejection - unparseable, so the status has to
            // come from the HttpException itself.
            var providerFailure = new InvalidRequestException(
                    "error, status code: %d, status: , message: %s".formatted(status, label),
                    new HttpException(status, label));

            var thrown = whenScoreTraceFails(providerFailure, Optional.empty());

            assertNonRetryable(thrown, status);
        }

        /**
         * The other half of the split, and the reason family-level classification was not enough: 408, 425
         * and 429 are client-error-family by numbering but transient in meaning. Dropping them on the first
         * failure - which any blanket "4xx is non-retryable" rule would do - would discard an evaluation
         * over a rate limit that clears in seconds.
         */
        @ParameterizedTest(name = "scoreTrace: transient {0} -> stays retryable")
        @CsvSource({
                "408, Request Timeout",
                "425, Too Early",
                "429, Too Many Requests",
                "500, Internal Server Error",
                "502, Bad Gateway",
                "503, Service Unavailable",
        })
        @DisplayName("A transient status stays retryable, so maxRetries is still honoured")
        void scoreTrace__whenTransientStatus__thenStaysRetryable(int status, String label) {
            var thrown = whenScoreTraceFails(new RuntimeException(new HttpException(status, label)),
                    Optional.empty());

            assertRetryable(thrown, status);
        }

        /**
         * Review finding on #8162: with no status on the wire, the mapper's code must NOT decide
         * retryability -- in either direction.
         *
         * <p>An earlier revision fell back to {@code providerError.getCode()} when
         * {@code findProviderHttpStatus} came up empty. But the mappers synthesize a code when they cannot
         * parse the body ({@code CustomLlmErrorMessage} defaults to 400, {@code OpenAiErrorMessage} to
         * 500), and nothing downstream can tell a genuinely parsed 400 from that default. So any
         * unparseable failure of a CustomLlm provider -- a connection reset, a proxy hiccup, a truncated
         * body -- was classified permanent and dropped on its first delivery. Silent loss of an evaluation
         * that a retry might well have completed.
         *
         * <p>The asymmetry settles it: needlessly retrying a genuinely permanent error wastes at most
         * {@code maxRetries} attempts, whereas dropping an unknown failure loses it forever. So <b>every</b>
         * mapper-only status is now retryable, 400 included. The permanent classification requires a real
         * wire status, which the cases above and below supply via {@code HttpException}.
         */
        @ParameterizedTest(name = "scoreTrace: mapper-only {0}, no wire status -> stays retryable")
        @CsvSource({"400", "401", "403", "408", "429", "500", "503"})
        @DisplayName("Without a wire status the mapper's code never makes a failure permanent")
        void scoreTrace__whenNoWireStatus__thenStaysRetryableWhateverTheMapperSays(int mappedStatus) {
            var thrown = whenScoreTraceFails(new RuntimeException("no HTTP status in this chain"),
                    Optional.of(new ErrorMessage(mappedStatus, "provider body says " + mappedStatus)));

            assertRetryable(thrown);
        }

        /**
         * Regression for the precedence bug caught in review of OPIK-8240.
         *
         * <p>The provider mappers synthesize a status when they cannot read one off the body:
         * {@code CustomLlmErrorMessage} defaults to 400, {@code OpenAiErrorMessage} to 500. If a synthetic
         * value outranks the code the provider actually put on the wire, retryability is decided from a
         * number nobody sent. This is the damaging direction: a transient provider outage hidden behind
         * CustomLlm's synthetic 400 would be classified permanent and dropped on its first delivery.
         */
        @ParameterizedTest(name = "transient wire {0} behind synthetic {1} -> stays retryable")
        @CsvSource({
                "503, 400",
                "429, 400",
        })
        @DisplayName("A transient status on the wire outranks a synthetic permanent mapper fallback")
        void scoreTrace__whenTransientWireStatusBehindSyntheticPermanent__thenStaysRetryable(
                int wireStatus, int syntheticStatus) {
            var providerFailure = new RuntimeException(new HttpException(wireStatus, "upstream said " + wireStatus));

            var thrown = whenScoreTraceFails(providerFailure,
                    Optional.of(new ErrorMessage(syntheticStatus, "synthetic mapper fallback")));

            assertRetryable(thrown, wireStatus);
        }

        /** The harmless direction of the same precedence rule, kept so the rule is pinned both ways. */
        @ParameterizedTest(name = "permanent wire {0} behind synthetic {1} -> stays non-retryable")
        @CsvSource({
                "400, 500",
                "403, 500",
        })
        @DisplayName("A permanent status on the wire outranks a synthetic transient mapper fallback")
        void scoreTrace__whenPermanentWireStatusBehindSyntheticTransient__thenStaysNonRetryable(
                int wireStatus, int syntheticStatus) {
            var providerFailure = new RuntimeException(new HttpException(wireStatus, "upstream said " + wireStatus));

            var thrown = whenScoreTraceFails(providerFailure,
                    Optional.of(new ErrorMessage(syntheticStatus, "synthetic mapper fallback")));

            assertNonRetryable(thrown, wireStatus);
        }

        /**
         * The shared production-shaped rows, run through scoreTrace. Split into a permanent and a transient
         * case rather than one case that branches: scoreTrace has no JAX-RS caller, so the thrown TYPE is
         * read for retryability rather than as an HTTP status, and each category deserves an assertion that
         * says which one it belongs to. Before OPIK-8240 this path answered every failure with a blanket
         * 500, which kept transient 408/429 retryable (correct) but also replayed permanent 400/401
         * failures that could never succeed (the redaction-limit incident).
         */
        @ParameterizedTest(name = "scoreTrace: when {0}, then non-retryable {2}")
        @MethodSource("permanentProviderStatuses")
        @DisplayName("Online scoring drops a permanent provider status instead of replaying it")
        void scoreTrace__whenPermanentProviderErrorUnparsed__thenNonRetryable(
                String testName, RuntimeException providerFailure, int expectedStatus, String expectedMessagePart) {
            var thrown = whenScoreTraceFails(providerFailure, Optional.empty());

            assertThat(thrown).hasMessageContaining(expectedMessagePart);
            assertNonRetryable(thrown, expectedStatus);
        }

        @ParameterizedTest(name = "scoreTrace: when {0}, then retryable as {2}")
        @MethodSource("transientProviderStatuses")
        @DisplayName("Online scoring keeps a transient provider status retryable, honouring maxRetries")
        void scoreTrace__whenTransientProviderErrorUnparsed__thenRetryable(
                String testName, RuntimeException providerFailure, int expectedStatus, String expectedMessagePart) {
            var thrown = whenScoreTraceFails(providerFailure, Optional.empty());

            assertThat(thrown).hasMessageContaining(expectedMessagePart);
            assertRetryable(thrown, expectedStatus);
        }

        /**
         * Review finding: the permanent cases above assert the thrown TYPE but not that the call was made once.
         * {@code InvalidRequestException} is already a {@code NonRetriableException}, so those cases pass with
         * {@code failFastOnPermanentFailure} deleted — they do not exercise the HTTP-status path they claim to.
         * These drive a raw {@code HttpException}, which is NOT a {@code NonRetriableException}, so only the
         * status classification can stop the retry.
         */
        @ParameterizedTest(name = "permanent HTTP {0} is attempted exactly once")
        @CsvSource({
                "400, Bad Request",
                "403, Forbidden",
                "404, Not Found",
                "413, Payload Too Large",
                "422, Unprocessable Entity",
        })
        @DisplayName("A permanent wire status does not consume the in-process retry budget")
        void scoreTrace__whenPermanentHttpStatus__thenNotRetriedInProcess(int status, String label) {
            when(llmProviderClientConfig.getMaxAttempts()).thenReturn(3);
            var retryingService = new ChatCompletionService(llmProviderClientConfig, llmProviderFactory);
            var chatRequest = ChatRequest.builder().messages(UserMessage.from("score this")).build();
            var modelParameters = podamFactory.manufacturePojo(LlmAsJudgeModelParameters.class);

            when(llmProviderFactory.getLanguageModel(anyString(), any())).thenReturn(chatModel);
            when(chatModel.chat(any(ChatRequest.class)))
                    .thenThrow(new RuntimeException(new HttpException(status, label)));
            lenient().when(llmProviderFactory.getService(anyString(), anyString()))
                    .thenReturn(llmProviderService);
            lenient().when(llmProviderService.getLlmProviderError(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> retryingService.scoreTrace(chatRequest, modelParameters, "workspace"))
                    .isInstanceOf(ClientErrorException.class);

            verify(chatModel, times(1)).chat(any(ChatRequest.class));
        }

        @ParameterizedTest(name = "transient HTTP {0} is still retried")
        @CsvSource({"429, Too Many Requests", "503, Service Unavailable"})
        @DisplayName("A transient wire status still consumes the retry budget")
        void scoreTrace__whenTransientHttpStatus__thenStillRetriedInProcess(int status, String label) {
            when(llmProviderClientConfig.getMaxAttempts()).thenReturn(3);
            var retryingService = new ChatCompletionService(llmProviderClientConfig, llmProviderFactory);
            var chatRequest = ChatRequest.builder().messages(UserMessage.from("score this")).build();
            var modelParameters = podamFactory.manufacturePojo(LlmAsJudgeModelParameters.class);

            when(llmProviderFactory.getLanguageModel(anyString(), any())).thenReturn(chatModel);
            when(chatModel.chat(any(ChatRequest.class)))
                    .thenThrow(new RuntimeException(new HttpException(status, label)));
            lenient().when(llmProviderFactory.getService(anyString(), anyString()))
                    .thenReturn(llmProviderService);
            lenient().when(llmProviderService.getLlmProviderError(any())).thenReturn(Optional.empty());

            // The status is now reported verbatim, so this asserts retryability rather than a flattened 500.
            assertRetryable(catchThrowable(
                    () -> retryingService.scoreTrace(chatRequest, modelParameters, "workspace")));

            verify(chatModel, times(EXPECTED_ATTEMPTS_AT_MAX_RETRIES_3)).chat(any(ChatRequest.class));
        }

        /**
         * VertexAI is one of two providers whose client raises no {@code HttpException} — the OpenAI Responses
         * SDK is the other, covered below. The Google Cloud SDK throws GAX {@code ApiException}, which
         * langchain4j also does not model as {@code NonRetriableException}, so a permanent Vertex failure was
         * retried in-process AND redelivered by the subscriber. The status now comes from GAX's own
         * transport-neutral translation, so it classifies like any other provider.
         */
        @ParameterizedTest(name = "GAX {0} -> HTTP {1}, non-retryable")
        @CsvSource({
                "INVALID_ARGUMENT, 400",
                "FAILED_PRECONDITION, 400",
                "OUT_OF_RANGE, 400",
                "UNAUTHENTICATED, 401",
                "PERMISSION_DENIED, 403",
                "NOT_FOUND, 404",
        })
        @DisplayName("A permanent VertexAI GAX failure is retired on the first delivery")
        void scoreTrace__whenPermanentGaxStatus__thenNonRetryable(StatusCode.Code code, int expectedStatus) {
            // Wrapped in RuntimeException because that is the shape VertexAiGeminiChatModel actually throws.
            var thrown = whenScoreTraceFails(new RuntimeException(gaxException(code, false)), Optional.empty());

            assertNonRetryable(thrown, expectedStatus);
        }

        @ParameterizedTest(name = "GAX {0} stays retryable as HTTP {1}")
        @CsvSource({
                "RESOURCE_EXHAUSTED, 429",
                "DEADLINE_EXCEEDED, 504",
                "UNAVAILABLE, 503",
                "INTERNAL, 500",
                "UNKNOWN, 500",
        })
        @DisplayName("A transient VertexAI GAX failure still honours maxRetries")
        void scoreTrace__whenTransientGaxStatus__thenRetryable(StatusCode.Code code, int expectedStatus) {
            var thrown = whenScoreTraceFails(new RuntimeException(gaxException(code, false)), Optional.empty());

            assertRetryable(thrown, expectedStatus);
        }

        /**
         * The safety valve. GAX's own {@code isRetryable()} can only ever prevent a drop, never cause one, so a code
         * that would otherwise map to a permanent status stays retryable when GAX says it is worth retrying. Guards
         * the cases where the gRPC-to-HTTP translation disagrees with retry semantics (ABORTED, CANCELLED).
         */
        @ParameterizedTest(name = "GAX {0} marked retryable stays retryable despite a permanent status")
        @CsvSource({"INVALID_ARGUMENT", "PERMISSION_DENIED", "ABORTED", "CANCELLED"})
        @DisplayName("GAX's own retryable verdict is never overridden into a drop")
        void scoreTrace__whenGaxSaysRetryable__thenRetryable(StatusCode.Code code) {
            var thrown = whenScoreTraceFails(new RuntimeException(gaxException(code, true)), Optional.empty());

            assertRetryable(thrown);
        }

        /**
         * Precedence is unchanged by the GAX addition: a real {@code HttpException} anywhere in the chain still wins
         * over anything a typed exception implies. A 503 on the wire must not be collapsed into GAX's permanent 400.
         */
        @Test
        @DisplayName("A wire HttpException still outranks a GAX status in the same chain")
        void scoreTrace__whenHttpExceptionAndGaxInSameChain__thenHttpExceptionWins() {
            var chain = new RuntimeException(
                    gaxException(StatusCode.Code.INVALID_ARGUMENT, false, new HttpException(503, "upstream 503")));

            var thrown = whenScoreTraceFails(chain, Optional.empty());

            assertRetryable(thrown);
        }

        @Test
        @DisplayName("A permanent GAX failure does not consume the in-process retry budget")
        void scoreTrace__whenPermanentGaxStatus__thenNotRetriedInProcess() {
            when(llmProviderClientConfig.getMaxAttempts()).thenReturn(3);
            var retryingService = new ChatCompletionService(llmProviderClientConfig, llmProviderFactory);
            var chatRequest = ChatRequest.builder().messages(UserMessage.from("score this")).build();
            var modelParameters = podamFactory.manufacturePojo(LlmAsJudgeModelParameters.class);

            when(llmProviderFactory.getLanguageModel(anyString(), any())).thenReturn(chatModel);
            when(chatModel.chat(any(ChatRequest.class)))
                    .thenThrow(new RuntimeException(gaxException(StatusCode.Code.INVALID_ARGUMENT, false)));
            lenient().when(llmProviderFactory.getService(anyString(), anyString()))
                    .thenReturn(llmProviderService);
            lenient().when(llmProviderService.getLlmProviderError(any())).thenReturn(Optional.empty());

            assertThatThrownBy(() -> retryingService.scoreTrace(chatRequest, modelParameters, "workspace"))
                    .isInstanceOf(ClientErrorException.class);

            // GAX ApiException is not a NonRetriableException, so without failFastOnPermanentFailure langchain4j's
            // RetryPolicy would replay a call that can never succeed.
            verify(chatModel, times(1)).chat(any(ChatRequest.class));
        }

        @Test
        @DisplayName("A transient GAX failure is still retried in-process")
        void scoreTrace__whenTransientGaxStatus__thenStillRetriedInProcess() {
            when(llmProviderClientConfig.getMaxAttempts()).thenReturn(3);
            var retryingService = new ChatCompletionService(llmProviderClientConfig, llmProviderFactory);
            var chatRequest = ChatRequest.builder().messages(UserMessage.from("score this")).build();
            var modelParameters = podamFactory.manufacturePojo(LlmAsJudgeModelParameters.class);

            when(llmProviderFactory.getLanguageModel(anyString(), any())).thenReturn(chatModel);
            when(chatModel.chat(any(ChatRequest.class)))
                    .thenThrow(new RuntimeException(gaxException(StatusCode.Code.UNAVAILABLE, false)));
            lenient().when(llmProviderFactory.getService(anyString(), anyString()))
                    .thenReturn(llmProviderService);
            lenient().when(llmProviderService.getLlmProviderError(any())).thenReturn(Optional.empty());

            // The status is now reported verbatim, so this asserts retryability rather than a flattened 500.
            assertRetryable(catchThrowable(
                    () -> retryingService.scoreTrace(chatRequest, modelParameters, "workspace")));

            verify(chatModel, times(EXPECTED_ATTEMPTS_AT_MAX_RETRIES_3)).chat(any(ChatRequest.class));
        }

        /**
         * Review finding, and my earlier refutation of it was wrong. I checked
         * {@code OpenAILlmServiceProvider.getLanguageModel} — which unconditionally calls
         * {@code newOpenAiChatLanguageModel} — and concluded the Responses SDK was unreachable from
         * scoreTrace. It isn't: that generator method itself switches on the pipeline mode and returns
         * {@code OpenAiOfficialResponsesChatModel} for {@code RESPONSES_API}, which is backed by openai-java
         * and raises {@code OpenAIServiceException}. That carries a real wire status but is neither an
         * {@code HttpException} nor a langchain4j typed exception, so a permanent 400 was falling through to
         * the retryable 500 and being replayed to exhaustion.
         */
        @ParameterizedTest(name = "Responses SDK {0} -> non-retryable")
        @CsvSource({"400", "403", "404", "422"})
        @DisplayName("A permanent OpenAI Responses failure is retired on the first delivery")
        void scoreTrace__whenPermanentResponsesSdkStatus__thenNonRetryable(int status) {
            var thrown = whenScoreTraceFails(new RuntimeException(responsesException(status)), Optional.empty());

            assertNonRetryable(thrown, status);
        }

        @ParameterizedTest(name = "Responses SDK {0} -> retryable, status preserved")
        @CsvSource({"408", "429", "500", "503"})
        @DisplayName("A transient OpenAI Responses failure keeps its status and stays retryable")
        void scoreTrace__whenTransientResponsesSdkStatus__thenRetryable(int status) {
            var thrown = whenScoreTraceFails(new RuntimeException(responsesException(status)), Optional.empty());

            assertRetryable(thrown, status);
        }

        /**
         * Review finding. {@code findProviderHttpStatus} filtered to error statuses only once, on the winner,
         * so a non-error {@code HttpException} anywhere in the chain satisfied {@code findFirst}, suppressed
         * the typed fallback, and was then discarded — leaving no status at all. For VertexAI and the OpenAI
         * Responses SDK that fallback is the only source of status, so a permanent 400 silently became a
         * retryable 500 and was replayed to exhaustion. The filter now runs at each stage.
         */
        @ParameterizedTest(name = "GAX {0} survives a non-error HttpException at {1} in the chain")
        @CsvSource({
                "INVALID_ARGUMENT, 302, 400",
                "PERMISSION_DENIED, 302, 403",
                "INVALID_ARGUMENT, 0, 400",
        })
        @DisplayName("A non-error status in the chain must not mask the real provider status")
        void scoreTrace__whenNonErrorHttpExceptionPrecedesGaxStatus__thenRealStatusWins(
                StatusCode.Code code, int noiseStatus, int expectedStatus) {
            // The GAX exception carries the stray HttpException as its cause, so the chain is
            // [ApiException, HttpException(noise)] -- the noise is found first by the HttpException pass.
            var providerFailure = gaxException(code, false, new HttpException(noiseStatus, "not an error"));

            var thrown = whenScoreTraceFails(new RuntimeException(providerFailure), Optional.empty());

            assertNonRetryable(thrown, expectedStatus);
        }

        @Test
        @DisplayName("A non-error status in the chain must not mask an OpenAI Responses status")
        void scoreTrace__whenNonErrorHttpExceptionPrecedesResponsesStatus__thenRealStatusWins() {
            var responses = responsesException(400, new HttpException(302, "not an error"));

            var thrown = whenScoreTraceFails(new RuntimeException(responses), Optional.empty());

            assertNonRetryable(thrown, 400);
        }

        /** Minimal stand-in for the openai-java SDK's exception: only {@code statusCode()} is consulted. */
        private static OpenAIServiceException responsesException(int status) {
            return responsesException(status, null);
        }

        private static OpenAIServiceException responsesException(int status, Throwable cause) {
            return new OpenAIServiceException("responses api failed with " + status, cause) {
                @Override
                public int statusCode() {
                    return status;
                }

                @Override
                public com.openai.core.http.Headers headers() {
                    return com.openai.core.http.Headers.builder().build();
                }

                @Override
                public com.openai.core.JsonValue body() {
                    return com.openai.core.JsonValue.from(null);
                }

                @Override
                public Optional<String> code() {
                    return Optional.empty();
                }

                @Override
                public Optional<String> param() {
                    return Optional.empty();
                }

                @Override
                public Optional<String> type() {
                    return Optional.empty();
                }
            };
        }

        private static ApiException gaxException(StatusCode.Code code, boolean retryable) {
            return gaxException(code, retryable, new RuntimeException("vertex transport failure"));
        }

        /** A transport-free {@link StatusCode}: gRPC and HTTP-JSON both reduce to the same neutral {@code Code}. */
        private static ApiException gaxException(StatusCode.Code code, boolean retryable, Throwable cause) {
            return new ApiException(cause, new StatusCode() {
                @Override
                public Code getCode() {
                    return code;
                }

                @Override
                public Object getTransportCode() {
                    return null;
                }
            }, retryable);
        }

        /** Drives scoreTrace to failure and returns what it threw. */
        private Throwable whenScoreTraceFails(RuntimeException providerFailure,
                Optional<ErrorMessage> mappedProviderError) {
            var chatRequest = ChatRequest.builder()
                    .messages(UserMessage.from(podamFactory.manufacturePojo(String.class)))
                    .build();
            var modelParameters = podamFactory.manufacturePojo(LlmAsJudgeModelParameters.class);
            var workspaceId = podamFactory.manufacturePojo(String.class);

            when(llmProviderFactory.getLanguageModel(anyString(), any())).thenReturn(chatModel);
            when(chatModel.chat(any(ChatRequest.class))).thenThrow(providerFailure);
            // scoreTrace no longer consults the provider mapper -- retryability comes from the wire status
            // alone. Kept lenient rather than deleted so these stay regression guards: if the mapper is ever
            // wired back in, the precedence cases below fail instead of silently passing.
            lenient().when(llmProviderFactory.getService(anyString(), anyString()))
                    .thenReturn(llmProviderService);
            lenient().when(llmProviderService.getLlmProviderError(any())).thenReturn(mappedProviderError);

            return catchThrowable(
                    () -> chatCompletionService.scoreTrace(chatRequest, modelParameters, workspaceId));
        }

        private void assertNonRetryable(Throwable thrown, int expectedStatus) {
            assertThat(PERMANENT_STATUSES)
                    .as("test-case error: %d is not one of the statuses this assertion is for", expectedStatus)
                    .contains(expectedStatus);
            assertThat(thrown)
                    .as("a permanent status must arrive as ClientErrorException, which the subscriber retires")
                    .isInstanceOf(ClientErrorException.class);
            assertThat(((WebApplicationException) thrown).getResponse().getStatus()).isEqualTo(expectedStatus);
        }

        /**
         * The provider's status is now reported verbatim, so this no longer pins a flattened 500 — it pins
         * the property that matters: the subscriber will redeliver rather than drop.
         */
        /**
         * For a failure that carried a real wire status: the status must be reported verbatim, not flattened.
         * Without this, reintroducing the blanket-500 workaround would pass every retryability assertion —
         * a 500 is retryable too, so "not permanent" alone cannot see the lie.
         */
        private void assertRetryable(Throwable thrown, int expectedStatus) {
            assertRetryable(thrown);
            assertThat(((WebApplicationException) thrown).getResponse().getStatus())
                    .as("a transient provider status must reach the subscriber verbatim, not as a blanket 500")
                    .isEqualTo(expectedStatus);
        }

        /**
         * Retryable is asserted against the literal permanent set, not the production predicate, so a
         * regression in {@code HttpStatusRetryability} cannot make these tests agree with broken behaviour.
         */
        private void assertRetryable(Throwable thrown) {
            assertThat(thrown).isInstanceOf(WebApplicationException.class);
            var status = ((WebApplicationException) thrown).getResponse().getStatus();
            assertThat(PERMANENT_STATUSES)
                    .as("status %d must stay retryable so the subscriber redelivers it", status)
                    .doesNotContain(status);
        }

        @ParameterizedTest(name = "streaming: when {0}, then stream status {2}")
        @MethodSource("providerStatusProvider")
        @DisplayName("Streaming delivers the provider status in-stream instead of a code-500 ErrorMessage")
        void createAndStreamResponse__whenProviderErrorUnparsed__thenStreamProviderStatus(
                String testName, RuntimeException providerFailure, int expectedStatus, String expectedMessagePart) {
            // Given
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";
            var handlers = mock(ChunkedOutputHandlers.class);

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            when(llmProviderService.getLlmProviderError(any())).thenReturn(Optional.empty());
            doAnswer(invocation -> {
                Consumer<Throwable> errorHandler = invocation.getArgument(4);
                errorHandler.accept(providerFailure);
                return null;
            }).when(llmProviderService).generateStream(any(), anyString(), any(), any(), any());

            // When — the streaming contract is HTTP 200 with the error delivered in-stream, so recovering the status
            // must change the ErrorMessage code only: nothing may escape here, or the playground would get an HTTP
            // status where it expects a stream
            assertThatCode(() -> chatCompletionService.createAndStreamResponse(request, workspaceId, handlers))
                    .doesNotThrowAnyException();

            // Then
            var errorCaptor = ArgumentCaptor.forClass(ErrorMessage.class);
            verify(handlers).handleError(errorCaptor.capture());
            assertThat(errorCaptor.getValue().getCode()).isEqualTo(expectedStatus);
            assertThat(errorCaptor.getValue().getMessage()).contains(expectedMessagePart);
        }

        @Test
        @DisplayName("a parsed provider envelope still wins, so existing classification is untouched")
        void create__whenProviderErrorParsed__thenEnvelopeStatusWins() {
            // Given — the envelope says 401 while the chain would yield 429; the parsed envelope is the more specific
            // provider verdict and must not be second-guessed by the fallback
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            when(llmProviderService.generate(any(), anyString())).thenThrow(new RateLimitException("slow down"));
            when(llmProviderService.getLlmProviderError(any()))
                    .thenReturn(Optional.of(new ErrorMessage(401, "Invalid API key")));

            // When
            var thrown = catchThrowable(() -> chatCompletionService.create(request, workspaceId));

            // Then
            assertThat(thrown).hasMessageContaining("Invalid API key");
            assertThat(((WebApplicationException) thrown).getResponse().getStatus()).isEqualTo(401);
        }
    }

    @Nested
    @DisplayName("Error Message Construction:")
    class ErrorMessageConstruction {

        private static Stream<Arguments> exactErrorMessageProvider() {
            return Stream.of(
                    Arguments.of(
                            "ConnectException with 'Connection refused'",
                            new ConnectException("Connection refused"),
                            "Unexpected error calling LLM provider: Service is unreachable. Please check the provider URL."),
                    Arguments.of(
                            "ClosedChannelException",
                            new ClosedChannelException(),
                            "Unexpected error calling LLM provider: Service is unreachable. Please check the provider URL."));
        }

        @ParameterizedTest(name = "when {0}, then return exact error message")
        @MethodSource("exactErrorMessageProvider")
        @DisplayName("Specific exceptions should produce exact error messages")
        void buildDetailedErrorMessage__whenSpecificException__thenReturnExactMessage(
                String testName, Exception causeException, String expectedMessage) {
            // Given
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";
            var runtimeException = new RuntimeException("Error", causeException);

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            when(llmProviderService.generate(any(), anyString())).thenThrow(runtimeException);
            when(llmProviderService.getLlmProviderError(any())).thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> chatCompletionService.create(request, workspaceId))
                    .isInstanceOf(InternalServerErrorException.class)
                    .hasMessage(expectedMessage);
        }

        @Test
        @DisplayName("when ConnectException with custom message, then include custom message")
        void buildDetailedErrorMessage__whenConnectExceptionWithCustomMessage__thenIncludeCustomMessage() {
            // Given
            var request = podamFactory.manufacturePojo(ChatCompletionRequest.class);
            var workspaceId = "test-workspace-id";
            var customMessage = "Connection to host:8080 failed";
            var connectException = new ConnectException(customMessage);
            var runtimeException = new RuntimeException("Connection error", connectException);

            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            when(llmProviderService.generate(any(), anyString())).thenThrow(runtimeException);
            when(llmProviderService.getLlmProviderError(any())).thenReturn(Optional.empty());

            // When & Then
            assertThatThrownBy(() -> chatCompletionService.create(request, workspaceId))
                    .isInstanceOf(InternalServerErrorException.class)
                    .hasMessageContaining("Unexpected error calling LLM provider")
                    .hasMessageContaining("Service is unreachable")
                    .hasMessageContaining(customMessage);
        }
    }
}
