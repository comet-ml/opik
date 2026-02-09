package com.comet.opik.domain.evaluators;

import com.fasterxml.jackson.databind.JsonNode;

record LlmAsJudgeCodeParameters(String name, Double temperature, Integer seed, JsonNode customParameters,
        Double throttling, Integer maxConcurrentRequests) {
}
