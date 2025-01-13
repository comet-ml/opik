package com.comet.opik.domain.llmproviders;

import dev.ai4j.openai4j.chat.AssistantMessage;
import dev.ai4j.openai4j.chat.ChatCompletionChoice;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.ai4j.openai4j.chat.Message;
import dev.ai4j.openai4j.chat.Role;
import dev.ai4j.openai4j.chat.SystemMessage;
import dev.ai4j.openai4j.chat.UserMessage;
import dev.ai4j.openai4j.shared.Usage;
import dev.langchain4j.model.anthropic.internal.api.AnthropicContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageRequest;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageResponse;
import dev.langchain4j.model.anthropic.internal.api.AnthropicMessage;
import dev.langchain4j.model.anthropic.internal.api.AnthropicMessageContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicRole;
import dev.langchain4j.model.anthropic.internal.api.AnthropicTextContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicUsage;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;

import java.util.List;

@Mapper
public interface LlmProviderAnthropicMapper {
    LlmProviderAnthropicMapper INSTANCE = Mappers.getMapper(LlmProviderAnthropicMapper.class);

    @Mapping(source = "response", target = "choices", qualifiedByName = "mapToChoices")
    @Mapping(source = "usage", target = "usage", qualifiedByName = "mapToUsage")
    ChatCompletionResponse toResponse(@NonNull AnthropicCreateMessageResponse response);

    @Mapping(source = "content", target = "message")
    @Mapping(source = "response.stopReason", target = "finishReason")
    ChatCompletionChoice toChoice(@NonNull AnthropicContent content, @NonNull AnthropicCreateMessageResponse response);

    @Mapping(source = "text", target = "content")
    AssistantMessage toAssistantMessage(@NonNull AnthropicContent content);

    @Mapping(expression = "java(request.model())", target = "model")
    @Mapping(expression = "java(request.stream())", target = "stream")
    @Mapping(expression = "java(request.temperature())", target = "temperature")
    @Mapping(expression = "java(request.topP())", target = "topP")
    @Mapping(expression = "java(request.stop())", target = "stopSequences")
    @Mapping(expression = "java(request.maxCompletionTokens())", target = "maxTokens")
    @Mapping(source = "request", target = "messages", qualifiedByName = "mapToMessages")
    @Mapping(source = "request", target = "system", qualifiedByName = "mapToSystemMessages")
    AnthropicCreateMessageRequest toCreateMessageRequest(@NonNull ChatCompletionRequest request);

    @Named("mapToChoices")
    default List<ChatCompletionChoice> mapToChoices(@NonNull AnthropicCreateMessageResponse response) {
        if (response.content == null || response.content.isEmpty()) {
            return List.of();
        }
        return response.content.stream().map(content -> toChoice(content, response)).toList();
    }

    @Named("mapToUsage")
    default Usage mapToUsage(AnthropicUsage usage) {
        if (usage == null) {
            return null;
        }

        return Usage.builder()
                .promptTokens(usage.inputTokens)
                .completionTokens(usage.outputTokens)
                .totalTokens(usage.inputTokens + usage.outputTokens)
                .build();
    }

    @Named("mapToMessages")
    default List<AnthropicMessage> mapToMessages(@NonNull ChatCompletionRequest request) {
        return request.messages().stream()
                .filter(message -> List.of(Role.ASSISTANT, Role.USER).contains(message.role()))
                .map(this::mapToAnthropicMessage).toList();
    }

    @Named("mapToSystemMessages")
    default List<AnthropicTextContent> mapToSystemMessages(@NonNull ChatCompletionRequest request) {
        return request.messages().stream()
                .filter(message -> message.role() == Role.SYSTEM)
                .map(this::mapToSystemMessage).toList();
    }

    default AnthropicMessage mapToAnthropicMessage(@NonNull Message message) {
        return switch (message) {
            case AssistantMessage assistantMessage -> AnthropicMessage.builder()
                    .role(AnthropicRole.ASSISTANT)
                    .content(List.of(new AnthropicTextContent(assistantMessage.content())))
                    .build();
            case UserMessage userMessage -> AnthropicMessage.builder()
                    .role(AnthropicRole.USER)
                    .content(List.of(toAnthropicMessageContent(userMessage.content())))
                    .build();
            default -> throw new BadRequestException("unexpected message role: " + message.role());
        };
    }

    default AnthropicMessageContent toAnthropicMessageContent(@NonNull Object rawContent) {
        if (rawContent instanceof String content) {
            return new AnthropicTextContent(content);
        }

        throw new BadRequestException("only text content is supported");
    }

    default AnthropicTextContent mapToSystemMessage(@NonNull Message message) {
        if (message.role() != Role.SYSTEM) {
            throw new BadRequestException("expecting only system role, got: " + message.role());
        }

        return new AnthropicTextContent(((SystemMessage) message).content());
    }
}
