package com.comet.opik.domain.optimization;

import com.comet.opik.api.OptimizationStudioConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.PythonEvaluatorConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.utils.RetryUtils;
import io.dropwizard.util.Duration;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.Response.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.test.StepVerifier;

import java.net.SocketTimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Optimization Studio Service Test")
class OptimizationStudioServiceTest {

    @Mock
    private Client client;

    private OpikConfiguration config;
    private PythonEvaluatorConfig pythonEvaluatorConfig;

    @Mock
    private WebTarget webTarget;

    @Mock
    private Invocation.Builder builder;

    @Mock
    private AsyncInvoker asyncInvoker;

    private OptimizationStudioService optimizationStudioService;

    @BeforeEach
    void setUp() {
        pythonEvaluatorConfig = new PythonEvaluatorConfig();
        pythonEvaluatorConfig.setUrl("http://localhost:8000");
        pythonEvaluatorConfig.setMaxRetryAttempts(3);
        pythonEvaluatorConfig.setMinRetryDelay(Duration.milliseconds(10));
        pythonEvaluatorConfig.setMaxRetryDelay(Duration.milliseconds(10));

        config = new OpikConfiguration();
        config.setPythonEvaluator(pythonEvaluatorConfig);

        optimizationStudioService = new OptimizationStudioService(new RetriableHttpClient(client), config);
    }

    private void setupHttpCallChain() {
        when(client.target(anyString())).thenReturn(webTarget);
        when(webTarget.request()).thenReturn(builder);
        when(builder.async()).thenReturn(asyncInvoker);
    }

    @Nested
    @DisplayName("GenerateCode Method Tests")
    class GenerateCodeTests {

        @Test
        @DisplayName("Should return response body string on successful HTTP 200 response")
        void generateCode_whenSuccessfulResponse_shouldReturnResponseBody() {
            // Given
            OptimizationStudioConfig studioConfig = OptimizationStudioConfig.builder()
                    .datasetName("test-dataset")
                    .prompt(OptimizationStudioConfig.StudioPrompt.builder()
                            .messages(java.util.List.of(
                                    OptimizationStudioConfig.StudioMessage.builder()
                                            .role("user")
                                            .content("test content")
                                            .build()))
                            .build())
                    .llmModel(OptimizationStudioConfig.StudioLlmModel.builder()
                            .model("gpt-4")
                            .build())
                    .evaluation(OptimizationStudioConfig.StudioEvaluation.builder()
                            .metrics(java.util.List.of(
                                    OptimizationStudioConfig.StudioMetric.builder()
                                            .type("accuracy")
                                            .build()))
                            .build())
                    .optimizer(OptimizationStudioConfig.StudioOptimizer.builder()
                            .type("grid")
                            .build())
                    .build();

            String expectedResponseBody = "def evaluate(input, output): return 1.0";

            setupHttpCallChain();

            Response successResponse = createMockResponse(Status.OK, expectedResponseBody);
            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(successResponse);
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When
            String actualResponse = optimizationStudioService.generateCode(studioConfig)
                    .block();

            // Then
            assertThat(actualResponse).isEqualTo(expectedResponseBody);
            verify(client).target("http://localhost:8000/v1/private/studio/code");
        }

        @Test
        @DisplayName("Should throw BadRequestException with response body message on HTTP 400")
        void generateCode_whenClientError_shouldThrowBadRequestException() {
            // Given
            OptimizationStudioConfig studioConfig = OptimizationStudioConfig.builder()
                    .datasetName("test-dataset")
                    .prompt(OptimizationStudioConfig.StudioPrompt.builder()
                            .messages(java.util.List.of(
                                    OptimizationStudioConfig.StudioMessage.builder()
                                            .role("user")
                                            .content("invalid content")
                                            .build()))
                            .build())
                    .llmModel(OptimizationStudioConfig.StudioLlmModel.builder()
                            .model("gpt-4")
                            .build())
                    .evaluation(OptimizationStudioConfig.StudioEvaluation.builder()
                            .metrics(java.util.List.of(
                                    OptimizationStudioConfig.StudioMetric.builder()
                                            .type("accuracy")
                                            .build()))
                            .build())
                    .optimizer(OptimizationStudioConfig.StudioOptimizer.builder()
                            .type("grid")
                            .build())
                    .build();

            String errorMessage = "Invalid request: missing required field";

            setupHttpCallChain();

            Response badRequestResponse = createMockResponse(Status.BAD_REQUEST, errorMessage);
            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(badRequestResponse);
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When & Then
            StepVerifier.create(optimizationStudioService.generateCode(studioConfig))
                    .expectErrorMatches(throwable -> {
                        assertThat(throwable).isInstanceOf(BadRequestException.class);
                        assertThat(throwable.getMessage()).isEqualTo(errorMessage);
                        return true;
                    })
                    .verify();

            // Should not retry for 400 errors
            verify(asyncInvoker, times(1)).post(any(Entity.class), any(InvocationCallback.class));
        }

        @Test
        @DisplayName("Should retry on server error and eventually throw InternalServerErrorException")
        void generateCode_whenServerError_shouldRetryAndThrowInternalServerErrorException() {
            // Given
            OptimizationStudioConfig studioConfig = OptimizationStudioConfig.builder()
                    .datasetName("test-dataset")
                    .prompt(OptimizationStudioConfig.StudioPrompt.builder()
                            .messages(java.util.List.of(
                                    OptimizationStudioConfig.StudioMessage.builder()
                                            .role("user")
                                            .content("test content")
                                            .build()))
                            .build())
                    .llmModel(OptimizationStudioConfig.StudioLlmModel.builder()
                            .model("gpt-4")
                            .build())
                    .evaluation(OptimizationStudioConfig.StudioEvaluation.builder()
                            .metrics(java.util.List.of(
                                    OptimizationStudioConfig.StudioMetric.builder()
                                            .type("accuracy")
                                            .build()))
                            .build())
                    .optimizer(OptimizationStudioConfig.StudioOptimizer.builder()
                            .type("grid")
                            .build())
                    .build();

            String errorMessage = "Internal server error occurred";

            setupHttpCallChain();

            // Create retryable error responses (503 Service Unavailable)
            Response errorResponse1 = createMockResponse(Status.SERVICE_UNAVAILABLE, errorMessage);
            Response errorResponse2 = createMockResponse(Status.SERVICE_UNAVAILABLE, errorMessage);
            Response errorResponse3 = createMockResponse(Status.SERVICE_UNAVAILABLE, errorMessage);
            Response errorResponse4 = createMockResponse(Status.SERVICE_UNAVAILABLE, errorMessage);

            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(errorResponse1);
                return null;
            }).doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(errorResponse2);
                return null;
            }).doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(errorResponse3);
                return null;
            }).doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(errorResponse4);
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When & Then
            StepVerifier.create(optimizationStudioService.generateCode(studioConfig))
                    .expectErrorMatches(throwable -> {
                        // When retries are exhausted, RetriableHttpClient throws RetryableHttpException
                        assertThat(throwable).isInstanceOf(RetryUtils.RetryableHttpException.class);
                        assertThat(throwable.getMessage())
                                .contains("Service temporarily unavailable (HTTP 503)")
                                .contains(errorMessage);
                        return true;
                    })
                    .verify();

            // Should retry maxRetryAttempts (3) + initial attempt = 4 total calls
            verify(asyncInvoker, times(4)).post(any(Entity.class), any(InvocationCallback.class));
        }

        @Test
        @DisplayName("Should retry on 504 Gateway Timeout and eventually throw RetryableHttpException when retries exhausted")
        void generateCode_whenGatewayTimeout_shouldRetryAndThrowRetryableHttpException() {
            // Given
            OptimizationStudioConfig studioConfig = OptimizationStudioConfig.builder()
                    .datasetName("test-dataset")
                    .prompt(OptimizationStudioConfig.StudioPrompt.builder()
                            .messages(java.util.List.of(
                                    OptimizationStudioConfig.StudioMessage.builder()
                                            .role("user")
                                            .content("test content")
                                            .build()))
                            .build())
                    .llmModel(OptimizationStudioConfig.StudioLlmModel.builder()
                            .model("gpt-4")
                            .build())
                    .evaluation(OptimizationStudioConfig.StudioEvaluation.builder()
                            .metrics(java.util.List.of(
                                    OptimizationStudioConfig.StudioMetric.builder()
                                            .type("accuracy")
                                            .build()))
                            .build())
                    .optimizer(OptimizationStudioConfig.StudioOptimizer.builder()
                            .type("grid")
                            .build())
                    .build();

            String errorMessage = "Gateway timeout";

            setupHttpCallChain();

            // Create retryable error responses (504 Gateway Timeout)
            Response timeoutResponse1 = createMockResponse(Status.GATEWAY_TIMEOUT, errorMessage);
            Response timeoutResponse2 = createMockResponse(Status.GATEWAY_TIMEOUT, errorMessage);
            Response timeoutResponse3 = createMockResponse(Status.GATEWAY_TIMEOUT, errorMessage);
            Response timeoutResponse4 = createMockResponse(Status.GATEWAY_TIMEOUT, errorMessage);

            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(timeoutResponse1);
                return null;
            }).doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(timeoutResponse2);
                return null;
            }).doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(timeoutResponse3);
                return null;
            }).doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(timeoutResponse4);
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When & Then
            StepVerifier.create(optimizationStudioService.generateCode(studioConfig))
                    .expectErrorMatches(throwable -> {
                        // When retries are exhausted, RetriableHttpClient throws RetryableHttpException
                        assertThat(throwable).isInstanceOf(RetryUtils.RetryableHttpException.class);
                        assertThat(throwable.getMessage())
                                .contains("Service temporarily unavailable (HTTP 504)")
                                .contains(errorMessage);
                        return true;
                    })
                    .verify();

            // Should retry maxRetryAttempts (3) + initial attempt = 4 total calls
            verify(asyncInvoker, times(4)).post(any(Entity.class), any(InvocationCallback.class));
        }

        @Test
        @DisplayName("Should throw InternalServerErrorException on non-retryable server error (500)")
        void generateCode_whenNonRetryableServerError_shouldThrowInternalServerErrorException() {
            // Given
            OptimizationStudioConfig studioConfig = OptimizationStudioConfig.builder()
                    .datasetName("test-dataset")
                    .prompt(OptimizationStudioConfig.StudioPrompt.builder()
                            .messages(java.util.List.of(
                                    OptimizationStudioConfig.StudioMessage.builder()
                                            .role("user")
                                            .content("test content")
                                            .build()))
                            .build())
                    .llmModel(OptimizationStudioConfig.StudioLlmModel.builder()
                            .model("gpt-4")
                            .build())
                    .evaluation(OptimizationStudioConfig.StudioEvaluation.builder()
                            .metrics(java.util.List.of(
                                    OptimizationStudioConfig.StudioMetric.builder()
                                            .type("accuracy")
                                            .build()))
                            .build())
                    .optimizer(OptimizationStudioConfig.StudioOptimizer.builder()
                            .type("grid")
                            .build())
                    .build();

            String errorMessage = "Internal server error occurred";

            setupHttpCallChain();

            // Create non-retryable server error response (500 Internal Server Error)
            Response serverErrorResponse = createMockResponse(Status.INTERNAL_SERVER_ERROR, errorMessage);
            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(serverErrorResponse);
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When & Then
            StepVerifier.create(optimizationStudioService.generateCode(studioConfig))
                    .expectErrorMatches(throwable -> {
                        assertThat(throwable).isInstanceOf(InternalServerErrorException.class);
                        assertThat(throwable.getMessage())
                                .contains("Python code generation failed (HTTP 500)")
                                .contains(errorMessage);
                        return true;
                    })
                    .verify();

            // Should not retry for non-retryable server errors (500 is not retryable)
            verify(asyncInvoker, times(1)).post(any(Entity.class), any(InvocationCallback.class));
        }

        @Test
        @DisplayName("Should retry on server error and eventually succeed")
        void generateCode_whenServerErrorThenSuccess_shouldRetryAndSucceed() {
            // Given
            OptimizationStudioConfig studioConfig = OptimizationStudioConfig.builder()
                    .datasetName("test-dataset")
                    .prompt(OptimizationStudioConfig.StudioPrompt.builder()
                            .messages(java.util.List.of(
                                    OptimizationStudioConfig.StudioMessage.builder()
                                            .role("user")
                                            .content("test content")
                                            .build()))
                            .build())
                    .llmModel(OptimizationStudioConfig.StudioLlmModel.builder()
                            .model("gpt-4")
                            .build())
                    .evaluation(OptimizationStudioConfig.StudioEvaluation.builder()
                            .metrics(java.util.List.of(
                                    OptimizationStudioConfig.StudioMetric.builder()
                                            .type("accuracy")
                                            .build()))
                            .build())
                    .optimizer(OptimizationStudioConfig.StudioOptimizer.builder()
                            .type("grid")
                            .build())
                    .build();

            String expectedResponseBody = "def evaluate(input, output): return 1.0";
            String errorMessage = "Service temporarily unavailable";

            setupHttpCallChain();

            // First two attempts return 503, third succeeds
            Response errorResponse1 = createMockResponse(Status.SERVICE_UNAVAILABLE, errorMessage);
            Response errorResponse2 = createMockResponse(Status.SERVICE_UNAVAILABLE, errorMessage);
            Response successResponse = createMockResponse(Status.OK, expectedResponseBody);

            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(errorResponse1);
                return null;
            }).doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(errorResponse2);
                return null;
            }).doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(successResponse);
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When
            String actualResponse = optimizationStudioService.generateCode(studioConfig)
                    .block();

            // Then
            assertThat(actualResponse).isEqualTo(expectedResponseBody);
            // Should retry 2 times + initial attempt = 3 total calls
            verify(asyncInvoker, times(3)).post(any(Entity.class), any(InvocationCallback.class));
        }

        @Test
        @DisplayName("Should handle error response with no entity body")
        void generateCode_whenErrorResponseNoEntity_shouldUseDefaultErrorMessage() {
            // Given
            OptimizationStudioConfig studioConfig = OptimizationStudioConfig.builder()
                    .datasetName("test-dataset")
                    .prompt(OptimizationStudioConfig.StudioPrompt.builder()
                            .messages(java.util.List.of(
                                    OptimizationStudioConfig.StudioMessage.builder()
                                            .role("user")
                                            .content("test content")
                                            .build()))
                            .build())
                    .llmModel(OptimizationStudioConfig.StudioLlmModel.builder()
                            .model("gpt-4")
                            .build())
                    .evaluation(OptimizationStudioConfig.StudioEvaluation.builder()
                            .metrics(java.util.List.of(
                                    OptimizationStudioConfig.StudioMetric.builder()
                                            .type("accuracy")
                                            .build()))
                            .build())
                    .optimizer(OptimizationStudioConfig.StudioOptimizer.builder()
                            .type("grid")
                            .build())
                    .build();

            setupHttpCallChain();

            Response badRequestResponse = createMockResponse(Status.BAD_REQUEST, null);
            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(badRequestResponse);
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When & Then
            StepVerifier.create(optimizationStudioService.generateCode(studioConfig))
                    .expectErrorMatches(throwable -> {
                        assertThat(throwable).isInstanceOf(BadRequestException.class);
                        assertThat(throwable.getMessage()).isEqualTo("Unknown error during code generation");
                        return true;
                    })
                    .verify();
        }

        @Test
        @DisplayName("Should handle error response with empty entity body")
        void generateCode_whenErrorResponseEmptyEntity_shouldUseDefaultErrorMessage() {
            // Given
            OptimizationStudioConfig studioConfig = OptimizationStudioConfig.builder()
                    .datasetName("test-dataset")
                    .prompt(OptimizationStudioConfig.StudioPrompt.builder()
                            .messages(java.util.List.of(
                                    OptimizationStudioConfig.StudioMessage.builder()
                                            .role("user")
                                            .content("test content")
                                            .build()))
                            .build())
                    .llmModel(OptimizationStudioConfig.StudioLlmModel.builder()
                            .model("gpt-4")
                            .build())
                    .evaluation(OptimizationStudioConfig.StudioEvaluation.builder()
                            .metrics(java.util.List.of(
                                    OptimizationStudioConfig.StudioMetric.builder()
                                            .type("accuracy")
                                            .build()))
                            .build())
                    .optimizer(OptimizationStudioConfig.StudioOptimizer.builder()
                            .type("grid")
                            .build())
                    .build();

            setupHttpCallChain();

            Response badRequestResponse = mock(Response.class);
            when(badRequestResponse.getStatusInfo()).thenReturn(Status.BAD_REQUEST);
            when(badRequestResponse.getStatus()).thenReturn(400);
            when(badRequestResponse.hasEntity()).thenReturn(true);
            when(badRequestResponse.bufferEntity()).thenReturn(true);
            when(badRequestResponse.readEntity(String.class)).thenReturn("   "); // Blank string

            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(badRequestResponse);
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When & Then
            StepVerifier.create(optimizationStudioService.generateCode(studioConfig))
                    .expectErrorMatches(throwable -> {
                        assertThat(throwable).isInstanceOf(BadRequestException.class);
                        assertThat(throwable.getMessage()).isEqualTo("Unknown error during code generation");
                        return true;
                    })
                    .verify();
        }

        @Test
        @DisplayName("Should handle error response parsing failure gracefully")
        void generateCode_whenErrorResponseParsingFails_shouldUseDefaultErrorMessage() {
            // Given
            OptimizationStudioConfig studioConfig = OptimizationStudioConfig.builder()
                    .datasetName("test-dataset")
                    .prompt(OptimizationStudioConfig.StudioPrompt.builder()
                            .messages(java.util.List.of(
                                    OptimizationStudioConfig.StudioMessage.builder()
                                            .role("user")
                                            .content("test content")
                                            .build()))
                            .build())
                    .llmModel(OptimizationStudioConfig.StudioLlmModel.builder()
                            .model("gpt-4")
                            .build())
                    .evaluation(OptimizationStudioConfig.StudioEvaluation.builder()
                            .metrics(java.util.List.of(
                                    OptimizationStudioConfig.StudioMetric.builder()
                                            .type("accuracy")
                                            .build()))
                            .build())
                    .optimizer(OptimizationStudioConfig.StudioOptimizer.builder()
                            .type("grid")
                            .build())
                    .build();

            setupHttpCallChain();

            Response serverErrorResponse = mock(Response.class);
            when(serverErrorResponse.getStatusInfo()).thenReturn(Status.INTERNAL_SERVER_ERROR);
            when(serverErrorResponse.getStatus()).thenReturn(500);
            when(serverErrorResponse.hasEntity()).thenReturn(true);
            when(serverErrorResponse.bufferEntity()).thenReturn(true);
            when(serverErrorResponse.readEntity(String.class))
                    .thenThrow(new ProcessingException("Failed to parse response"));

            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(serverErrorResponse);
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When & Then
            StepVerifier.create(optimizationStudioService.generateCode(studioConfig))
                    .expectErrorMatches(throwable -> {
                        assertThat(throwable).isInstanceOf(InternalServerErrorException.class);
                        assertThat(throwable.getMessage())
                                .contains("Python code generation failed (HTTP 500)")
                                .contains("Unknown error during code generation");
                        return true;
                    })
                    .verify();
        }

        @Test
        @DisplayName("Should handle timeout exception and retry")
        void generateCode_whenTimeoutException_shouldRetry() {
            // Given
            OptimizationStudioConfig studioConfig = OptimizationStudioConfig.builder()
                    .datasetName("test-dataset")
                    .prompt(OptimizationStudioConfig.StudioPrompt.builder()
                            .messages(java.util.List.of(
                                    OptimizationStudioConfig.StudioMessage.builder()
                                            .role("user")
                                            .content("test content")
                                            .build()))
                            .build())
                    .llmModel(OptimizationStudioConfig.StudioLlmModel.builder()
                            .model("gpt-4")
                            .build())
                    .evaluation(OptimizationStudioConfig.StudioEvaluation.builder()
                            .metrics(java.util.List.of(
                                    OptimizationStudioConfig.StudioMetric.builder()
                                            .type("accuracy")
                                            .build()))
                            .build())
                    .optimizer(OptimizationStudioConfig.StudioOptimizer.builder()
                            .type("grid")
                            .build())
                    .build();

            String expectedResponseBody = "def evaluate(input, output): return 1.0";

            setupHttpCallChain();

            Response successResponse = createMockResponse(Status.OK, expectedResponseBody);

            // First call throws timeout, second succeeds
            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.failed(new ProcessingException("Request timeout",
                        new SocketTimeoutException("Connection timed out")));
                return null;
            }).doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(successResponse);
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When
            String actualResponse = optimizationStudioService.generateCode(studioConfig)
                    .block();

            // Then
            assertThat(actualResponse).isEqualTo(expectedResponseBody);
            verify(asyncInvoker, times(2)).post(any(Entity.class), any(InvocationCallback.class));
        }
    }

    /**
     * Creates a mock Response with the specified status and entity body.
     *
     * @param status The HTTP status
     * @param entityBody The response body as a String (can be null)
     * @return A mocked Response instance
     */
    private Response createMockResponse(Status status, String entityBody) {
        Response mockResponse = mock(Response.class);

        lenient().when(mockResponse.getStatusInfo()).thenReturn(status);
        lenient().when(mockResponse.getStatus()).thenReturn(status.getStatusCode());
        lenient().when(mockResponse.hasEntity()).thenReturn(entityBody != null && !entityBody.isBlank());
        lenient().when(mockResponse.bufferEntity()).thenReturn(entityBody != null && !entityBody.isBlank());

        if (entityBody != null && !entityBody.isBlank()) {
            lenient().when(mockResponse.readEntity(String.class)).thenReturn(entityBody);
        }

        return mockResponse;
    }
}
