package com.comet.opik.infrastructure.llm;

import dev.langchain4j.model.output.FinishReason;
import lombok.experimental.UtilityClass;

@UtilityClass
public class OpenAiCompatFinishReasons {

    public String toWireValue(FinishReason finishReason) {
        if (finishReason == null) {
            return null;
        }
        return switch (finishReason) {
            case STOP -> "stop";
            case LENGTH -> "length";
            case TOOL_EXECUTION -> "tool_calls";
            case CONTENT_FILTER -> "content_filter";
            default -> "other";
        };
    }
}
