package com.comet.opik.infrastructure;

import com.comet.opik.utils.RetryUtils;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.AsyncInvoker;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the two things that are invisible from a caller's perspective: that every exit closes the
 * response, and that the diagnostic body carried into {@code RetryableHttpException} is bounded.
 * <p>
 * Both matter most under the retry, which is exactly when they are hardest to observe in production:
 * a leak or an unbounded log line is multiplied per attempt, against a service that is already
 * failing.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RetriableHttpClientTest {

    private static final String URL = "http://localhost:8000";
    private static final int MAX_DIAGNOSTIC_BODY_LENGTH = 512;

    @Mock
    private Client client;
    @Mock
    private WebTarget webTarget;
    @Mock
    private Invocation.Builder builder;
    @Mock
    private AsyncInvoker asyncInvoker;
    @Mock
    private Response response;

    private RetriableHttpClient retriableHttpClient;

    @BeforeEach
    void setUp() {
        retriableHttpClient = new RetriableHttpClient(client);
        when(client.target(anyString())).thenReturn(webTarget);
        when(webTarget.request()).thenReturn(builder);
        when(builder.async()).thenReturn(asyncInvoker);
        when(asyncInvoker.method(anyString(), any(Entity.class), any(InvocationCallback.class)))
                .thenAnswer(invocation -> {
                    InvocationCallback<Response> callback = invocation.getArgument(2);
                    callback.completed(response);
                    return null;
                });
    }

    private RetriableHttpClient.Request<String> request(int maxRetries) {
        return RetriableHttpClient.Request.<String>builder()
                .requestFunction(c -> c.target(URL))
                .retryPolicy(RetryUtils.handleHttpErrors(maxRetries, Duration.ofMillis(1), Duration.ofMillis(2)))
                .body(Entity.json("{}"))
                .responseFunction(r -> r.readEntity(String.class))
                .build();
    }

    @ParameterizedTest
    @ValueSource(ints = {503, 504})
    void executePostWithRetry__whenRetryableStatus__thenBodyBoundedAndResponseClosed(int status) {
        var oversizedBody = "x".repeat(4_000);
        when(response.getStatus()).thenReturn(status);
        when(response.bufferEntity()).thenReturn(true);
        when(response.readEntity(String.class)).thenReturn(oversizedBody);

        assertThatThrownBy(() -> retriableHttpClient.executePostWithRetry(request(0)).block())
                .isInstanceOf(RetryUtils.RetryableHttpException.class)
                .satisfies(thrown -> {
                    // The prefix plus an abbreviation of the body, never the whole 4,000 characters.
                    assertThat(thrown.getMessage()).hasSizeLessThan(MAX_DIAGNOSTIC_BODY_LENGTH + 100);
                    assertThat(thrown.getMessage()).doesNotContain(oversizedBody);
                    assertThat(thrown.getMessage()).endsWith("...");
                });

        verify(response).close();
    }

    /**
     * The retryable path used to return {@code Mono.error} without closing, so each attempt leaked a
     * pooled connection. One close per attempt is the property that matters.
     */
    @Test
    void executePostWithRetry__whenRetryableStatusRetried__thenEveryAttemptClosesItsResponse() {
        when(response.getStatus()).thenReturn(503);
        when(response.bufferEntity()).thenReturn(true);
        when(response.readEntity(String.class)).thenReturn("unavailable");

        assertThatThrownBy(() -> retriableHttpClient.executePostWithRetry(request(2)).block())
                .isInstanceOf(RetryUtils.RetryableHttpException.class);

        verify(response, times(3)).close(); // 1 initial attempt + 2 retries
    }

    @Test
    void executePostWithRetry__whenBodyUnreadable__thenFallsBackWithoutThrowingFromErrorHandling() {
        when(response.getStatus()).thenReturn(503);
        when(response.bufferEntity()).thenReturn(true);
        when(response.readEntity(String.class)).thenThrow(new ProcessingException("stream closed"));

        assertThatThrownBy(() -> retriableHttpClient.executePostWithRetry(request(0)).block())
                .isInstanceOf(RetryUtils.RetryableHttpException.class)
                .hasMessageContaining("<unreadable body>");

        verify(response).close();
    }

    @Test
    void executePostWithRetry__whenSuccessful__thenResponseClosedAfterTransform() {
        when(response.getStatus()).thenReturn(200);
        when(response.readEntity(String.class)).thenReturn("payload");

        var result = retriableHttpClient.executePostWithRetry(request(0)).block();

        assertThat(result).isEqualTo("payload");
        verify(response).close();
    }

    /**
     * A transform that throws must not unwind past the close, and must not be retried: a decode
     * failure is deterministic, so retrying it only multiplies the work.
     */
    @Test
    void executePostWithRetry__whenTransformThrows__thenResponseClosedAndNotRetried() {
        when(response.getStatus()).thenReturn(200);
        when(response.readEntity(String.class)).thenThrow(new IllegalStateException("bad payload"));

        assertThatThrownBy(() -> retriableHttpClient.executePostWithRetry(request(2)).block())
                .isInstanceOf(IllegalStateException.class);

        verify(response).close();
        verify(response, never()).bufferEntity();
    }
}
