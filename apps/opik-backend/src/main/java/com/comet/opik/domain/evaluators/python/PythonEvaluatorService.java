package com.comet.opik.domain.evaluators.python;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.google.common.base.Preconditions;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;
import reactor.util.retry.RetryBackoffSpec;

import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static com.comet.opik.domain.evaluators.python.TraceThreadPythonEvaluatorRequest.ChatMessage;

@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class PythonEvaluatorService {

    private static final String URL_TEMPLATE = "%s/v1/private/evaluators/python";

    private final @NonNull Client client;
    private final @NonNull OpikConfiguration config;

    public List<PythonScoreResult> evaluate(@NonNull String code, Map<String, String> data) {
        Preconditions.checkArgument(MapUtils.isNotEmpty(data), "Argument 'data' must not be empty");
        var request = PythonEvaluatorRequest.builder()
                .code(code)
                .data(data)
                .build();

        return executeWithRetry(() -> performHttpCall(request));
    }

    public List<PythonScoreResult> evaluateThread(@NonNull String code, List<ChatMessage> context) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(context), "Argument 'context' must not be empty");
        var request = TraceThreadPythonEvaluatorRequest.builder()
                .code(code)
                .data(context)
                .build();

        return executeWithRetry(() -> performHttpCall(request));
    }

    private List<PythonScoreResult> executeWithRetry(HttpCallSupplier operation) {
        return Mono.fromCallable(operation::execute)
                .retryWhen(createRetrySpec())
                .subscribeOn(Schedulers.boundedElastic())
                .block();
    }

    private RetryBackoffSpec createRetrySpec() {
        var retryConfig = config.getPythonEvaluator();

        return Retry.backoff(retryConfig.getMaxRetryAttempts(),
                Duration.ofMillis(retryConfig.getRetryDelay().toMilliseconds()))
                .doBeforeRetry(retrySignal -> log.warn("Retrying Python evaluation, attempt '{}': '{}'",
                        retrySignal.totalRetries() + 1,
                        retrySignal.failure().getMessage()))
                .onRetryExhaustedThrow((retryBackoffSpec, retrySignal) -> {
                    log.error("Failed to execute Python evaluation after '{}' attempts",
                            retrySignal.totalRetries() + 1,
                            retrySignal.failure());
                    return retrySignal.failure();
                })
                .filter(this::isRetryableException);
    }

    private List<PythonScoreResult> performHttpCall(Object request) {
        try (var response = client.target(URL_TEMPLATE.formatted(config.getPythonEvaluator().getUrl()))
                .request()
                .post(Entity.json(request))) {
            return processResponse(response);
        }
    }

    private List<PythonScoreResult> processResponse(Response response) {
        var statusCode = response.getStatus();

        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            return response.readEntity(PythonEvaluatorResponse.class).scores();
        }

        String errorMessage = extractErrorMessage(response);

        if (statusCode == 400) {
            throw new BadRequestException(errorMessage);
        } else if (isRetryableStatusCode(statusCode)) {
            // Create a custom retryable exception for 503/504 status codes
            throw new RetryableServiceException(
                    "Service temporarily unavailable (HTTP " + statusCode + "): " + errorMessage, statusCode);
        } else {
            throw new InternalServerErrorException(
                    "Python evaluation failed (HTTP " + statusCode + "): " + errorMessage);
        }
    }

    private String extractErrorMessage(Response response) {
        String errorMessage = "Unknown error during Python evaluation";

        if (response.hasEntity() && response.bufferEntity()) {
            try {
                errorMessage = response.readEntity(PythonEvaluatorErrorResponse.class).error();
            } catch (RuntimeException parseErrorResponse) {
                log.warn("Failed to parse error response, falling back to parsing string", parseErrorResponse);
                try {
                    errorMessage = response.readEntity(String.class);
                } catch (RuntimeException parseStringResponse) {
                    log.warn("Failed to parse error string response", parseStringResponse);
                }
            }
        }

        return errorMessage;
    }

    private boolean isRetryableException(Throwable exception) {
        return switch (exception) {
            case RetryableServiceException ignored -> true;
            case ProcessingException processingException -> {
                Throwable cause = processingException.getCause();
                yield isTimeoutOrNetworkException(cause);
            }
            case InternalServerErrorException ignored -> false;
            default -> false;
        };
    }

    private boolean isRetryableStatusCode(int statusCode) {
        return statusCode == 503 || statusCode == 504;
    }

    /**
     * Checks if the exception is a timeout or network-related exception.
     */
    private boolean isTimeoutOrNetworkException(Throwable cause) {
        if (cause == null) {
            return false;
        }

        return cause instanceof SocketTimeoutException ||
                cause instanceof java.net.ConnectException ||
                cause instanceof java.net.SocketException ||
                cause instanceof java.io.InterruptedIOException ||
                cause instanceof java.util.concurrent.TimeoutException;
    }

    @FunctionalInterface
    private interface HttpCallSupplier {
        List<PythonScoreResult> execute();
    }

    /**
     * Custom exception for retryable HTTP service errors (503, 504).
     * This avoids Jakarta validation issues with InternalServerErrorException.
     */
    @Getter
    private static class RetryableServiceException extends RuntimeException {

        private final int statusCode;

        public RetryableServiceException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }
    }
}