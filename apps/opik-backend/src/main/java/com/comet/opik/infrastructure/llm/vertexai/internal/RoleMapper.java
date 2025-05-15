package com.comet.opik.infrastructure.llm.vertexai.internal;

import dev.langchain4j.data.message.ChatMessageType;
import lombok.experimental.UtilityClass;

@UtilityClass
class RoleMapper {

    static String map(ChatMessageType type) {
        return switch (type) {
            case TOOL_EXECUTION_RESULT, USER -> "user";
            case AI -> "model";
            case SYSTEM -> "system";
            default -> throw new IllegalStateException("Unexpected value: " + type);
        };
    }
}
