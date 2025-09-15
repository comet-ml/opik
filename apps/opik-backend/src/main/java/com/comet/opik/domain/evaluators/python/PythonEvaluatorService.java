package com.comet.opik.domain.evaluators.python;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.utils.RetryUtils;
import com.google.common.base.Preconditions;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;

import java.util.List;
import java.util.Map;

import static com.comet.opik.domain.evaluators.python.TraceThreadPythonEvaluatorRequest.ChatMessage;

@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class PythonEvaluatorService {

    private static final String URL_TEMPLATE = "%s/v1/private/evaluators/python";

    private final @NonNull RetriableHttpClient client;
    private final @NonNull OpikConfiguration config;

    public List<PythonScoreResult> evaluate(@NonNull String code, Map<String, String> data) {
        Preconditions.checkArgument(MapUtils.isNotEmpty(data), "Argument 'data' must not be empty");
        var request = PythonEvaluatorRequest.builder()
                .code(code)
                .data(data)
                .build();

        return executeWithRetry(Entity.json(request));
    }

    public List<PythonScoreResult> evaluateThread(@NonNull String code, List<ChatMessage> context) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(context), "Argument 'context' must not be empty");
        TraceThreadPythonEvaluatorRequest request = TraceThreadPythonEvaluatorRequest.builder()
                .code(code)
                .data(context)
                .build();

        return executeWithRetry(Entity.json(request));
    }

    private List<PythonScoreResult> executeWithRetry(Entity<?> request) {
        return RetriableHttpClient.newPost(c -> c.target(URL_TEMPLATE.formatted(config.getPythonEvaluator().getUrl())))
                .withRetryPolicy(RetryUtils.handleHttpErrors(config.getPythonEvaluator().getMaxRetryAttempts(),
                        config.getPythonEvaluator().getMinRetryDelay().toJavaDuration(),
                        config.getPythonEvaluator().getMaxRetryDelay().toJavaDuration()))
                .withRequestBody(request)
                .withResponse(this::processResponse)
                .execute(client);
    }

    private List<PythonScoreResult> processResponse(Response response) {
        int statusCode = response.getStatus();
        Response.StatusType statusInfo = response.getStatusInfo();

        if (statusInfo.getFamily() == Response.Status.Family.SUCCESSFUL) {
            return response.readEntity(PythonEvaluatorResponse.class).scores();
        }

        String errorMessage = extractErrorMessage(response);

        if (statusCode == 400) {
            throw new BadRequestException(errorMessage);
        }

        throw new InternalServerErrorException(
                "Python evaluation failed (HTTP " + statusCode + "): " + errorMessage);
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

}