package com.comet.opik.domain.evaluators.python;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import lombok.Builder;

import java.util.Map;

/**
 * Request payload for evaluating a common metric.
 * This is sent to the Python backend's /common-metrics/{metric_id}/score endpoint.
 */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record CommonMetricEvaluatorRequest(
        Map<String, Object> initConfig,
        Map<String, String> scoringKwargs) {
}
