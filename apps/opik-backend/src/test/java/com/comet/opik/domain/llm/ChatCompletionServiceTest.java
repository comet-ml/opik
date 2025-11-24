package com.comet.opik.domain.llm;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.podam.PodamFactoryUtils;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.InternalServerErrorException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.net.ConnectException;
import java.nio.channels.ClosedChannelException;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
