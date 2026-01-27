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
    private static final String COMMON_METRIC_URL_TEMPLATE = "%s/v1/private/evaluators/common-metrics/%s/score";

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

    /**
     * Evaluates a common metric from the SDK by its ID.
     *
     * @param metricId     The ID of the common metric (e.g., "contains", "equals")
     * @param initConfig   Configuration parameters for the metric's __init__ method
     * @param scoringKwargs Arguments to pass to the metric's score method
     * @return List of score results
     */
    public List<PythonScoreResult> evaluateCommonMetric(
            @NonNull String metricId,
            Map<String, Object> initConfig,
            Map<String, String> scoringKwargs) {
        Preconditions.checkArgument(MapUtils.isNotEmpty(scoringKwargs), "Argument 'scoringKwargs' must not be empty");

        var request = CommonMetricEvaluatorRequest.builder()
                .initConfig(initConfig)
                .scoringKwargs(scoringKwargs)
                .build();

        String url = COMMON_METRIC_URL_TEMPLATE.formatted(config.getPythonEvaluator().getUrl(), metricId);
        log.info("Evaluating common metric '{}' at '{}'", metricId, url);

        return RetriableHttpClient.newPost(c -> c.target(url))
                .withRetryPolicy(RetryUtils.handleHttpErrors(config.getPythonEvaluator().getMaxRetryAttempts(),
                        config.getPythonEvaluator().getMinRetryDelay().toJavaDuration(),
                        config.getPythonEvaluator().getMaxRetryDelay().toJavaDuration()))
                .withRequestBody(Entity.json(request))
                .withResponse(this::processResponse)
                .execute(client);
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