package com.comet.opik.infrastructure.llm.vertexai;

import dev.ai4j.openai4j.chat.AssistantMessage;
import dev.ai4j.openai4j.chat.ChatCompletionChoice;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.ai4j.openai4j.chat.Message;
import dev.ai4j.openai4j.chat.Role;
import dev.ai4j.openai4j.shared.Usage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.Response;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper
interface VertexAIMapper {

    String ERR_UNEXPECTED_ROLE = "unexpected role '%s'";
    String ERR_ROLE_MSG_TYPE_MISMATCH = "role and message instance are not matching, role: '%s', instance: '%s'";

    VertexAIMapper INSTANCE = Mappers.getMapper(VertexAIMapper.class);

    default ChatMessage toChatMessage(@NonNull Message message) {
        if (!List.of(Role.ASSISTANT, Role.USER, Role.SYSTEM).contains(message.role())) {
            throw new BadRequestException(ERR_UNEXPECTED_ROLE.formatted(message.role()));
        }

        switch (message.role()) {
            case ASSISTANT -> {
                if (message instanceof AssistantMessage assistantMessage) {
                    return AiMessage.from(assistantMessage.content());
                }
            }
            case USER -> {
                if (message instanceof dev.ai4j.openai4j.chat.UserMessage userMessage) {
                    return UserMessage.from(userMessage.content().toString());
                }
            }
            case SYSTEM -> {
                if (message instanceof dev.ai4j.openai4j.chat.SystemMessage systemMessage) {
                    return SystemMessage.from(systemMessage.content());
                }
            }
        }

        throw new BadRequestException(ERR_ROLE_MSG_TYPE_MISMATCH.formatted(message.role(),
                message.getClass().getSimpleName()));
    }

    @Mapping(expression = "java(request.model())", target = "model")
    @Mapping(source = "response", target = "choices", qualifiedByName = "mapToChoices")
    @Mapping(source = "response", target = "usage", qualifiedByName = "mapToUsage")
    ChatCompletionResponse toChatCompletionResponse(
            @NonNull ChatCompletionRequest request, @NonNull Response<AiMessage> response);

    @Named("mapToChoices")
    default List<ChatCompletionChoice> mapToChoices(@NonNull Response<AiMessage> response) {
        return List.of(ChatCompletionChoice.builder()
                .message(AssistantMessage.builder().content(response.content().text()).build())
                .build());
    }

    @Named("mapToUsage")
    default Usage mapToUsage(@NonNull Response<AiMessage> response) {
        return Usage.builder()
                .promptTokens(response.tokenUsage().inputTokenCount())
                .completionTokens(response.tokenUsage().outputTokenCount())
                .totalTokens(response.tokenUsage().totalTokenCount())
                .build();
    }
}
