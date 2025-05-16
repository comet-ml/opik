package com.comet.opik.infrastructure.llm.vertexai.internal;

import dev.langchain4j.data.message.ChatMessageType;

public class RoleMapper {

    static String map(ChatMessageType type) {
        switch (type) {
            case TOOL_EXECUTION_RESULT :
            case USER :
                return "user";
            case AI :
                return "model";
            case SYSTEM :
                return "system";
        }
        throw new IllegalArgumentException(type + " is not allowed.");
    }
}
