package com.comet.opik.utils;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.openai.internal.chat.AssistantMessage;
import dev.langchain4j.model.openai.internal.chat.FunctionMessage;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.Role;
import dev.langchain4j.model.openai.internal.chat.SystemMessage;
import dev.langchain4j.model.openai.internal.chat.ToolMessage;
import dev.langchain4j.model.openai.internal.chat.UserMessage;

import java.io.IOException;

/**
 * The Message interface of openai4j has not appropriate deserialization support for all its polymorphic implementors
 * such as UserMessage, AssistantMessage etc. so deserialization fails.
 * As we can't annotate them Message interface with JsonTypeInfo and JsonSubTypes, solving this issue by creating
 * a custom deserializer.
 */
public class OpenAiMessageJsonDeserializer extends JsonDeserializer<Message> {

    public static final OpenAiMessageJsonDeserializer INSTANCE = new OpenAiMessageJsonDeserializer();

    @Override
    public Message deserialize(JsonParser jsonParser, DeserializationContext context) throws IOException {
        JsonNode jsonNode = jsonParser.readValueAsTree();
        var role = context.readTreeAsValue(jsonNode.get("role"), Role.class);
        return switch (role) {
            case SYSTEM -> context.readTreeAsValue(jsonNode, SystemMessage.class);
            case USER -> context.readTreeAsValue(jsonNode, UserMessage.class);
            case ASSISTANT -> context.readTreeAsValue(jsonNode, AssistantMessage.class);
            case TOOL -> context.readTreeAsValue(jsonNode, ToolMessage.class);
            case FUNCTION -> context.readTreeAsValue(jsonNode, FunctionMessage.class);
        };
    }
}
