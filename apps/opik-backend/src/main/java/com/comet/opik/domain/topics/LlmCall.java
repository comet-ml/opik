package com.comet.opik.domain.topics;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** The kinds of LLM call a run makes. The service picks the call's deadline and its metric label from the kind. */
@Getter
@RequiredArgsConstructor
public enum LlmCall {
    /** Names the topics over the groups. */
    AUTHOR("author");

    private final String value;
}
