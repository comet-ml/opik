package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum LlmProvider {

    @JsonProperty("openai")
    OPEN_AI;
}
