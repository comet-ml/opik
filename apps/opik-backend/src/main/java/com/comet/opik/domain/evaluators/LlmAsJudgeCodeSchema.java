package com.comet.opik.domain.evaluators;

import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchemaType;

record LlmAsJudgeCodeSchema(String name, LlmAsJudgeOutputSchemaType type, String description) {
}
