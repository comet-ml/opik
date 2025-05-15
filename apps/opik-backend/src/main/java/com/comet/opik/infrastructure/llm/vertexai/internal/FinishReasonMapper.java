package com.comet.opik.infrastructure.llm.vertexai.internal;

import com.google.cloud.vertexai.api.Candidate;
import dev.langchain4j.model.output.FinishReason;

public class FinishReasonMapper {

    static FinishReason map(Candidate.FinishReason finishReason) {
        return switch (finishReason) {
            case STOP -> FinishReason.STOP;
            case MAX_TOKENS -> FinishReason.LENGTH;
            case SAFETY -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.OTHER;
        };
    }
}
