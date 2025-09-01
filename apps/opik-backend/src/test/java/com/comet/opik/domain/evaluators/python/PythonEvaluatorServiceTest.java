package com.comet.opik.domain.evaluators.python;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.PythonEvaluatorConfig;
import com.comet.opik.podam.PodamFactoryUtils;
import io.dropwizard.util.Duration;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Python Evaluator Service Test")
class PythonEvaluatorServiceTest {

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    @Mock
    private Client client;

    @Mock
    private OpikConfiguration config;

    @Mock
    private PythonEvaluatorConfig pythonEvaluatorConfig;

    @Mock
    private WebTarget webTarget;

    @Mock
    private Invocation.Builder builder;

    @Mock
    private Response response;

    private PythonEvaluatorService pythonEvaluatorService;

    @BeforeEach
    void setUp() {
        lenient().when(config.getPythonEvaluator()).thenReturn(pythonEvaluatorConfig);
        lenient().when(pythonEvaluatorConfig.getUrl()).thenReturn("http://localhost:8000");
        lenient().when(pythonEvaluatorConfig.getMaxRetryAttempts()).thenReturn(4);
        lenient().when(pythonEvaluatorConfig.getRetryDelay()).thenReturn(Duration.milliseconds(100));

        pythonEvaluatorService = new PythonEvaluatorService(client, config);
    }

    private void setupHttpCall() {
        when(client.target(anyString())).thenReturn(webTarget);
        when(webTarget.request()).thenReturn(builder);
        lenient().when(builder.post(any(Entity.class))).thenReturn(response);
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

            setupHttpCall();
            when(response.getStatusInfo()).thenReturn(Response.Status.OK);
            when(response.readEntity(PythonEvaluatorResponse.class)).thenReturn(pythonResponse);

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

            setupHttpCall();

            // First 2 calls return 503, 3rd call succeeds
            when(response.getStatusInfo())
                    .thenReturn(Response.Status.SERVICE_UNAVAILABLE)
                    .thenReturn(Response.Status.SERVICE_UNAVAILABLE)
                    .thenReturn(Response.Status.OK);
            when(response.getStatus())
                    .thenReturn(503)
                    .thenReturn(503)
                    .thenReturn(200);
            when(response.readEntity(PythonEvaluatorResponse.class)).thenReturn(pythonResponse);

            // When
            var actualScores = pythonEvaluatorService.evaluate(code, data);

            // Then
            assertThat(actualScores).isEqualTo(expectedScores);
            verify(builder, times(3)).post(any(Entity.class));
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

            setupHttpCall();

            // First call returns 504, 2nd call succeeds
            when(response.getStatusInfo())
                    .thenReturn(Response.Status.GATEWAY_TIMEOUT)
                    .thenReturn(Response.Status.OK);
            when(response.getStatus())
                    .thenReturn(504)
                    .thenReturn(200);
            when(response.readEntity(PythonEvaluatorResponse.class)).thenReturn(pythonResponse);

            // When
            var actualScores = pythonEvaluatorService.evaluate(code, data);

            // Then
            assertThat(actualScores).isEqualTo(expectedScores);
            verify(builder, times(2)).post(any(Entity.class));
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

            setupHttpCall();

            // First call throws timeout, 2nd call succeeds
            when(builder.post(any(Entity.class)))
                    .thenThrow(new ProcessingException("Request timeout",
                            new SocketTimeoutException("Connection timed out")))
                    .thenReturn(response);
            when(response.getStatusInfo()).thenReturn(Response.Status.OK);
            when(response.readEntity(PythonEvaluatorResponse.class)).thenReturn(pythonResponse);

            // When
            var actualScores = pythonEvaluatorService.evaluate(code, data);

            // Then
            assertThat(actualScores).isEqualTo(expectedScores);
            verify(builder, times(2)).post(any(Entity.class));
        }

        @Test
        void evaluate__whenMaxRetriesExceeded__shouldThrowException() {
            // Given
            var code = "def evaluate(input, output): return 1.0";
            var data = Map.of("input", "test input", "output", "test output");

            setupHttpCall();

            // All calls return 503
            when(response.getStatusInfo()).thenReturn(Response.Status.SERVICE_UNAVAILABLE);
            when(response.getStatus()).thenReturn(503);

            // When & Then
            assertThatThrownBy(() -> pythonEvaluatorService.evaluate(code, data))
                    .isInstanceOf(RuntimeException.class);

            // Should retry 4 times + initial attempt = 5 total calls
            verify(builder, times(5)).post(any(Entity.class));
        }

        @Test
        void evaluate__when400Error__shouldNotRetryAndThrowImmediately() {
            // Given
            var code = "def evaluate(input, output): return 1.0";
            var data = Map.of("input", "test input", "output", "test output");
            var errorResponse = PythonEvaluatorErrorResponse.builder()
                    .error("Invalid Python code")
                    .build();

            setupHttpCall();
            when(response.getStatusInfo()).thenReturn(Response.Status.BAD_REQUEST);
            when(response.getStatus()).thenReturn(400);
            when(response.hasEntity()).thenReturn(true);
            when(response.bufferEntity()).thenReturn(true);
            when(response.readEntity(PythonEvaluatorErrorResponse.class)).thenReturn(errorResponse);

            // When & Then
            assertThatThrownBy(() -> pythonEvaluatorService.evaluate(code, data))
                    .isInstanceOf(BadRequestException.class)
                    .hasMessageContaining("Invalid Python code");

            // Should not retry for 400 errors
            verify(builder, times(1)).post(any(Entity.class));
        }

        @Test
        void evaluate__whenNonRetryableProcessingException__shouldThrowImmediately() {
            // Given
            var code = "def evaluate(input, output): return 1.0";
            var data = Map.of("input", "test input", "output", "test output");

            setupHttpCall();
            when(builder.post(any(Entity.class)))
                    .thenThrow(new ProcessingException("Connection refused"));

            // When & Then
            assertThatThrownBy(() -> pythonEvaluatorService.evaluate(code, data))
                    .isInstanceOf(ProcessingException.class)
                    .hasMessageContaining("Connection refused");

            // Should not retry for non-timeout processing exceptions
            verify(builder, times(1)).post(any(Entity.class));
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

            setupHttpCall();
            when(response.getStatusInfo()).thenReturn(Response.Status.OK);
            when(response.readEntity(PythonEvaluatorResponse.class)).thenReturn(pythonResponse);

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

            setupHttpCall();

            // First call returns 503, 2nd call succeeds
            when(response.getStatusInfo())
                    .thenReturn(Response.Status.SERVICE_UNAVAILABLE)
                    .thenReturn(Response.Status.OK);
            when(response.getStatus())
                    .thenReturn(503)
                    .thenReturn(200);
            when(response.readEntity(PythonEvaluatorResponse.class)).thenReturn(pythonResponse);

            // When
            var actualScores = pythonEvaluatorService.evaluateThread(code, context);

            // Then
            assertThat(actualScores).isEqualTo(expectedScores);
            verify(builder, times(2)).post(any(Entity.class));
        }

        @Test
        void evaluateThread__whenMaxRetriesExceeded__shouldThrowException() {
            // Given
            var code = "def evaluate(messages): return 1.0";
            var context = List.of(podamFactory.manufacturePojo(ChatMessage.class));

            setupHttpCall();

            // All calls return 504
            when(response.getStatusInfo()).thenReturn(Response.Status.GATEWAY_TIMEOUT);
            when(response.getStatus()).thenReturn(504);

            // When & Then
            assertThatThrownBy(() -> pythonEvaluatorService.evaluateThread(code, context))
                    .isInstanceOf(RuntimeException.class);

            // Should retry 4 times + initial attempt = 5 total calls
            verify(builder, times(5)).post(any(Entity.class));
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

            setupHttpCall();
            when(response.getStatusInfo()).thenReturn(Response.Status.BAD_REQUEST);
            when(response.getStatus()).thenReturn(400);
            when(response.hasEntity()).thenReturn(true);
            when(response.bufferEntity()).thenReturn(true);

            // First readEntity call fails, second succeeds with string
            when(response.readEntity(PythonEvaluatorErrorResponse.class))
                    .thenThrow(new RuntimeException("JSON parsing failed"));
            when(response.readEntity(String.class)).thenReturn("Custom error message");

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

            setupHttpCall();
            when(response.getStatusInfo()).thenReturn(Response.Status.INTERNAL_SERVER_ERROR);
            when(response.getStatus()).thenReturn(500);
            when(response.hasEntity()).thenReturn(true);
            when(response.bufferEntity()).thenReturn(true);

            // Both readEntity calls fail
            when(response.readEntity(PythonEvaluatorErrorResponse.class))
                    .thenThrow(new RuntimeException("JSON parsing failed"));
            when(response.readEntity(String.class))
                    .thenThrow(new RuntimeException("String parsing failed"));

            // When & Then
            assertThatThrownBy(() -> pythonEvaluatorService.evaluate(code, data))
                    .isInstanceOf(InternalServerErrorException.class)
                    .hasMessageContaining(
                            "Python evaluation failed (HTTP 500): Unknown error during Python evaluation");
        }
    }
}
