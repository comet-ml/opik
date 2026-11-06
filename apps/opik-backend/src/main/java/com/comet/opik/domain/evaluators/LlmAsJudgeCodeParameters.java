package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.LlmAsJudgeModelApi;
import com.fasterxml.jackson.databind.JsonNode;

record LlmAsJudgeCodeParameters(String name, Double temperature, Integer seed, JsonNode customParameters,
        LlmAsJudgeModelApi api) {
}
