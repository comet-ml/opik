package com.comet.opik.api.evaluators;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.RequiredArgsConstructor;

/**
 * The provider API a judge rule calls when its model is served by more than one. Absent means the model's default:
 * the Decisions API for decisions-only models such as TypeSafe Jev, chat for every other model.
 */
@RequiredArgsConstructor
public enum LlmAsJudgeModelApi {

    DECISIONS("decisions");

    @JsonValue
    private final String value;
}
