package com.comet.opik.domain.llm;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.ChunkedOutputHandlers;
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
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.util.Optional;
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

        @ParameterizedTest(name = "scoreTrace: when {0}, then stay a retryable 500")
        @MethodSource("providerStatusProvider")
        @DisplayName("Online scoring deliberately keeps the blanket 500, so the subscriber still retries")
        void scoreTrace__whenProviderErrorUnparsed__thenStayRetryable(
                String testName, RuntimeException providerFailure, int expectedStatus, String expectedMessagePart) {
            // Given — scoreTrace has no JAX-RS caller: the three OnlineScoring*LlmAsJudgeScorer subscribers are the
            // only callers, so a recovered status would reach no HTTP client. It would, however, reach
            // BaseRedisSubscriber.NON_RETRYABLE_EXCEPTIONS, which lists ClientErrorException — so a recovered 429 or
            // 408 (both RetriableException upstream) would be acked and dropped instead of retried up to
            // onlineScoring.maxRetries. Whatever the provider reported, this path must stay a 500.
            var chatRequest = ChatRequest.builder().messages(UserMessage.from("score this")).build();
            var modelParameters = podamFactory.manufacturePojo(LlmAsJudgeModelParameters.class);
            var workspaceId = "test-workspace-id";

            when(llmProviderFactory.getLanguageModel(anyString(), any())).thenReturn(chatModel);
            when(chatModel.chat(any(ChatRequest.class))).thenThrow(providerFailure);
            when(llmProviderFactory.getService(anyString(), anyString())).thenReturn(llmProviderService);
            when(llmProviderService.getLlmProviderError(any())).thenReturn(Optional.empty());

            // When
            var thrown = catchThrowable(
                    () -> chatCompletionService.scoreTrace(chatRequest, modelParameters, workspaceId));

            // Then — InternalServerErrorException is absent from NON_RETRYABLE_EXCEPTIONS, which is what keeps the
            // evaluation retryable; expectedStatus is deliberately unused here
            assertThat(thrown)
                    .isInstanceOf(InternalServerErrorException.class)
                    .isNotInstanceOf(ClientErrorException.class)
                    .hasMessageContaining(expectedMessagePart);
            assertThat(((WebApplicationException) thrown).getResponse().getStatus()).isEqualTo(500);
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
