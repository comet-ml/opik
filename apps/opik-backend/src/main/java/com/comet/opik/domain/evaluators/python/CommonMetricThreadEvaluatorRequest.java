package com.comet.opik.domain.evaluators.python;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.util.List;
import java.util.Map;

import static com.comet.opik.domain.evaluators.python.TraceThreadPythonEvaluatorRequest.ChatMessage;

/**
 * Request payload for evaluating a common metric with thread/conversation context.
 * This is sent to the Python backend's /common-metrics/{metric_id}/score endpoint.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CommonMetricThreadEvaluatorRequest(
        Map<String, Object> initConfig,
        List<ChatMessage> data) {
}
