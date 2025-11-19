package com.comet.opik.domain.evaluators.python;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.PythonEvaluatorConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.podam.PodamFactoryUtils;
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
import uk.co.jemos.podam.api.PodamFactory;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Map;

import static com.comet.opik.domain.evaluators.python.TraceThreadPythonEvaluatorRequest.ChatMessage;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Python Evaluator Service Test")
class PythonEvaluatorServiceTest {

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @Mock
    private Client client;

    private OpikConfiguration config;

    @Mock
    private WebTarget webTarget;

    @Mock
    private Invocation.Builder builder;

    @Mock
    private AsyncInvoker asyncInvoker;

    @Mock
    private Response response;

    private PythonEvaluatorService pythonEvaluatorService;

    private PythonEvaluatorConfig pythonEvaluatorConfig;

    @BeforeEach
    void setUp() {
        pythonEvaluatorConfig = new PythonEvaluatorConfig();
        pythonEvaluatorConfig.setUrl("http://localhost:8000");
        pythonEvaluatorConfig.setMaxRetryAttempts(4);
        pythonEvaluatorConfig.setMinRetryDelay(Duration.milliseconds(100));
        pythonEvaluatorConfig.setMaxRetryDelay(Duration.milliseconds(100));

        config = new OpikConfiguration();
        config.setPythonEvaluator(pythonEvaluatorConfig);

        pythonEvaluatorService = new PythonEvaluatorService(new RetriableHttpClient(client), config);
    }

    private void setupHttpCallChain() {
        when(client.target(anyString())).thenReturn(webTarget);
        when(webTarget.request()).thenReturn(builder);
        when(builder.async()).thenReturn(asyncInvoker);
    }

    @Nested
    @DisplayName("Evaluate Method Tests")
    class EvaluateTests {

        @Test
        void evaluate__whenValidRequest__shouldReturnResults() {
            // Given
            var code = "def evaluate(input, output): return 1.0";
            var data = Map.of("input", "test input", "output", "test output");
            var expectedScores = List.of(
                    podamFactory.manufacturePojo(PythonScoreResult.class),
                    podamFactory.manufacturePojo(PythonScoreResult.class));
            var pythonResponse = PythonEvaluatorResponse.builder()
                    .scores(expectedScores)
                    .build();

            // Setup HTTP call chain
            setupHttpCallChain();

            // Setup HTTP call with immutable response
            Response successResponse = createMockResponse(Status.OK, pythonResponse);
            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(successResponse);
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When
            var actualScores = pythonEvaluatorService.evaluate(code, data);

            // Then
            assertThat(actualScores).isEqualTo(expectedScores);
            verify(client).target("http://localhost:8000/v1/private/evaluators/python");
        }

        @Test
        void evaluate__whenEmptyData__shouldThrowIllegalArgumentException() {
            // Given
            var code = "def evaluate(input, output): return 1.0";
            var emptyData = Map.<String, String>of();

            // When & Then
            assertThatThrownBy(() -> pythonEvaluatorService.evaluate(code, emptyData))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Argument 'data' must not be empty");
        }

        @Test
        void evaluate__when503Error__shouldRetryAndEventuallySucceed() {
            // Given
            var code = "def evaluate(input, output): return 1.0";
            var data = Map.of("input", "test input", "output", "test output");
            var expectedScores = List.of(podamFactory.manufacturePojo(PythonScoreResult.class));
            var pythonResponse = PythonEvaluatorResponse.builder()
                    .scores(expectedScores)
                    .build();

            // Setup HTTP call chain
            setupHttpCallChain();

            // Create immutable responses for each retry attempt
            Response errorResponse1 = createMockResponse(Status.SERVICE_UNAVAILABLE, null);
            Response errorResponse2 = createMockResponse(Status.SERVICE_UNAVAILABLE, null);
            Response successResponse = createMockResponse(Status.OK, pythonResponse);

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
            var actualScores = pythonEvaluatorService.evaluate(code, data);

            // Then
            assertThat(actualScores).isEqualTo(expectedScores);
            verify(asyncInvoker, times(3)).post(any(Entity.class), any(InvocationCallback.class));
        }

        @Test
        void evaluate__when504Error__shouldRetryAndEventuallySucceed() {
            // Given
            var code = "def evaluate(input, output): return 1.0";
            var data = Map.of("input", "test input", "output", "test output");
            var expectedScores = List.of(podamFactory.manufacturePojo(PythonScoreResult.class));
            var pythonResponse = PythonEvaluatorResponse.builder()
                    .scores(expectedScores)
                    .build();

            // Setup HTTP call chain
            setupHttpCallChain();

            // Create immutable responses for each retry attempt
            Response timeoutResponse = createMockResponse(Status.GATEWAY_TIMEOUT, null);
            Response successResponse = createMockResponse(Status.OK, pythonResponse);

            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(timeoutResponse);
                return null;
            }).doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(successResponse);
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When
            var actualScores = pythonEvaluatorService.evaluate(code, data);

            // Then
            assertThat(actualScores).isEqualTo(expectedScores);
            verify(asyncInvoker, times(2)).post(any(Entity.class), any(InvocationCallback.class));
        }

        @Test
        void evaluate__whenTimeoutException__shouldRetryAndEventuallySucceed() {
            // Given
            var code = "def evaluate(input, output): return 1.0";
            var data = Map.of("input", "test input", "output", "test output");
            var expectedScores = List.of(podamFactory.manufacturePojo(PythonScoreResult.class));
            var pythonResponse = PythonEvaluatorResponse.builder()
                    .scores(expectedScores)
                    .build();

            // Setup HTTP call chain
            setupHttpCallChain();

            // Create immutable response for success case
            Response successResponse = createMockResponse(Status.OK, pythonResponse);

            // First call throws timeout, 2nd call succeeds
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
            var actualScores = pythonEvaluatorService.evaluate(code, data);

            // Then
            assertThat(actualScores).isEqualTo(expectedScores);
            verify(asyncInvoker, times(2)).post(any(Entity.class), any(InvocationCallback.class));
        }

        @Test
        void evaluate__whenMaxRetriesExceeded__shouldThrowException() {
            // Given
            var code = "def evaluate(input, output): return 1.0";
            var data = Map.of("input", "test input", "output", "test output");

            // Setup HTTP call chain
            setupHttpCallChain();

            // Create immutable error response for all attempts
            Response errorResponse = createMockResponse(Status.SERVICE_UNAVAILABLE, null);

            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(errorResponse);
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When & Then
            assertThatThrownBy(() -> pythonEvaluatorService.evaluate(code, data))
                    .isInstanceOf(RuntimeException.class);

            // Should retry 4 times + initial attempt = 5 total calls
            verify(asyncInvoker, times(5)).post(any(Entity.class), any(InvocationCallback.class));
        }

        @Test
        void evaluate__when400Error__shouldNotRetryAndThrowImmediately() {
            // Given
            var code = "def evaluate(input, output): return 1.0";
            var data = Map.of("input", "test input", "output", "test output");
            var errorResponse = PythonEvaluatorErrorResponse.builder()
                    .error("Invalid Python code")
                    .build();

            // Setup HTTP call chain
            setupHttpCallChain();

            // Create immutable error response
            Response badRequestResponse = createMockResponse(Status.BAD_REQUEST, errorResponse);

            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(badRequestResponse);
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When & Then
            assertThatThrownBy(() -> pythonEvaluatorService.evaluate(code, data))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid Python code");

            // Should not retry for 400 errors
            verify(asyncInvoker, times(1)).post(any(Entity.class), any(InvocationCallback.class));
        }

        @Test
        void evaluate__whenNonRetryableProcessingException__shouldThrowImmediately() {
            // Given
            var code = "def evaluate(input, output): return 1.0";
            var data = Map.of("input", "test input", "output", "test output");

            // Setup HTTP call chain
            when(client.target(anyString())).thenReturn(webTarget);
            when(webTarget.request()).thenReturn(builder);
            when(builder.async()).thenReturn(asyncInvoker);

            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.failed(new ProcessingException("Connection refused"));
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When & Then
            assertThatThrownBy(() -> pythonEvaluatorService.evaluate(code, data))
                    .isInstanceOf(ProcessingException.class)
                    .hasMessageContaining("Connection refused");

            // Should not retry for non-timeout processing exceptions
            verify(asyncInvoker, times(1)).post(any(Entity.class), any(InvocationCallback.class));
        }
    }

    @Nested
    @DisplayName("EvaluateThread Method Tests")
    class EvaluateThreadTests {

        @Test
        void evaluateThread__whenValidRequest__shouldReturnResults() {
            // Given
            var code = "def evaluate(messages): return 1.0";
            var context = List.of(
                    podamFactory.manufacturePojo(ChatMessage.class),
                    podamFactory.manufacturePojo(ChatMessage.class));
            var expectedScores = List.of(
                    podamFactory.manufacturePojo(PythonScoreResult.class));
            var pythonResponse = PythonEvaluatorResponse.builder()
                    .scores(expectedScores)
                    .build();

            // Setup HTTP call chain
            setupHttpCallChain();

            // Create immutable success response
            Response successResponse = createMockResponse(Status.OK, pythonResponse);

            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(successResponse);
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When
            var actualScores = pythonEvaluatorService.evaluateThread(code, context);

            // Then
            assertThat(actualScores).isEqualTo(expectedScores);
            verify(client).target("http://localhost:8000/v1/private/evaluators/python");
        }

        @Test
        void evaluateThread__whenEmptyContext__shouldThrowIllegalArgumentException() {
            // Given
            var code = "def evaluate(messages): return 1.0";
            var emptyContext = List.<ChatMessage>of();

            // When & Then
            assertThatThrownBy(() -> pythonEvaluatorService.evaluateThread(code, emptyContext))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Argument 'context' must not be empty");
        }

        @Test
        void evaluateThread__when503Error__shouldRetryAndEventuallySucceed() {
            // Given
            var code = "def evaluate(messages): return 1.0";
            var context = List.of(podamFactory.manufacturePojo(ChatMessage.class));
            var expectedScores = List.of(podamFactory.manufacturePojo(PythonScoreResult.class));
            var pythonResponse = PythonEvaluatorResponse.builder()
                    .scores(expectedScores)
                    .build();

            // Setup HTTP call chain
            setupHttpCallChain();

            // Create immutable responses for each retry attempt
            Response errorResponse = createMockResponse(Status.SERVICE_UNAVAILABLE, null);
            Response successResponse = createMockResponse(Status.OK, pythonResponse);

            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(errorResponse);
                return null;
            }).doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(successResponse);
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When
            var actualScores = pythonEvaluatorService.evaluateThread(code, context);

            // Then
            assertThat(actualScores).isEqualTo(expectedScores);
            verify(asyncInvoker, times(2)).post(any(Entity.class), any(InvocationCallback.class));
        }

        @Test
        void evaluateThread__whenMaxRetriesExceeded__shouldThrowException() {
            // Given
            var code = "def evaluate(messages): return 1.0";
            var context = List.of(podamFactory.manufacturePojo(ChatMessage.class));

            // Setup HTTP call chain
            setupHttpCallChain();

            // Create immutable error response for all attempts
            Response timeoutResponse = createMockResponse(Status.GATEWAY_TIMEOUT, null);

            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(timeoutResponse);
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When & Then
            assertThatThrownBy(() -> pythonEvaluatorService.evaluateThread(code, context))
                    .isInstanceOf(RuntimeException.class);

            // Should retry 4 times + initial attempt = 5 total calls
            verify(asyncInvoker, times(5)).post(any(Entity.class), any(InvocationCallback.class));
        }
    }

    @Nested
    @DisplayName("Error Response Parsing Tests")
    class ErrorResponseParsingTests {

        @Test
        void evaluate__whenErrorResponseParsingFails__shouldFallbackToStringParsing() {
            // Given
            var code = "def evaluate(input, output): return 1.0";
            var data = Map.of("input", "test input", "output", "test output");

            // Setup HTTP call chain
            setupHttpCallChain();

            // Create immutable error response with fallback string behavior
            Response badRequestResponse = mock(Response.class);
            when(badRequestResponse.getStatusInfo()).thenReturn(Status.BAD_REQUEST);
            when(badRequestResponse.getStatus()).thenReturn(400);
            when(badRequestResponse.hasEntity()).thenReturn(true);
            when(badRequestResponse.bufferEntity()).thenReturn(true);
            // First readEntity call fails, second succeeds with string
            when(badRequestResponse.readEntity(PythonEvaluatorErrorResponse.class))
                    .thenThrow(new RuntimeException("JSON parsing failed"));
            when(badRequestResponse.readEntity(String.class)).thenReturn("Custom error message");

            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(badRequestResponse);
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When & Then
            assertThatThrownBy(() -> pythonEvaluatorService.evaluate(code, data))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Custom error message");
        }

        @Test
        void evaluate__whenBothErrorResponseParsingFails__shouldUseDefaultMessage() {
            // Given
            var code = "def evaluate(input, output): return 1.0";
            var data = Map.of("input", "test input", "output", "test output");

            // Setup HTTP call chain
            setupHttpCallChain();

            // Create immutable error response with both parsing failures
            Response serverErrorResponse = mock(Response.class);
            when(serverErrorResponse.getStatusInfo()).thenReturn(Status.INTERNAL_SERVER_ERROR);
            when(serverErrorResponse.getStatus()).thenReturn(500);
            when(serverErrorResponse.hasEntity()).thenReturn(true);
            when(serverErrorResponse.bufferEntity()).thenReturn(true);
            // Both readEntity calls fail
            when(serverErrorResponse.readEntity(PythonEvaluatorErrorResponse.class))
                    .thenThrow(new RuntimeException("JSON parsing failed"));
            when(serverErrorResponse.readEntity(String.class))
                    .thenThrow(new RuntimeException("String parsing failed"));

            doAnswer(invocation -> {
                InvocationCallback<Response> callback = invocation.getArgument(1);
                callback.completed(serverErrorResponse);
                return null;
            }).when(asyncInvoker).post(any(Entity.class), any(InvocationCallback.class));

            // When & Then
            assertThatThrownBy(() -> pythonEvaluatorService.evaluate(code, data))
                    .isInstanceOf(InternalServerErrorException.class)
                    .hasMessageContaining(
                            "Python evaluation failed (HTTP 500): Unknown error during Python evaluation");
        }
    }

    private Response createMockResponse(Status status, Object entity) {
        Response mockResponse = mock(Response.class);

        lenient().when(mockResponse.getStatusInfo()).thenReturn(status);
        lenient().when(mockResponse.getStatus()).thenReturn(status.getStatusCode());
        lenient().when(mockResponse.hasEntity()).thenReturn(entity != null);
        lenient().when(mockResponse.bufferEntity()).thenReturn(entity != null);

        if (entity != null) {
            if (entity instanceof PythonEvaluatorResponse) {
                lenient().when(mockResponse.readEntity(PythonEvaluatorResponse.class))
                        .thenReturn((PythonEvaluatorResponse) entity);
            } else if (entity instanceof PythonEvaluatorErrorResponse) {
                lenient().when(mockResponse.readEntity(PythonEvaluatorErrorResponse.class))
                        .thenReturn((PythonEvaluatorErrorResponse) entity);
            } else if (entity instanceof String) {
                lenient().when(mockResponse.readEntity(String.class)).thenReturn((String) entity);
            }
        }

        return mockResponse;
    }

}
