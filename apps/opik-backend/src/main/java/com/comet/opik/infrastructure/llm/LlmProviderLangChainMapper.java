package com.comet.opik.infrastructure.llm;

import com.comet.opik.infrastructure.llm.gemini.GeminiErrorObject;
import com.comet.opik.utils.JsonUtils;
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
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;
import org.slf4j.Logger;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

@Mapper
public interface LlmProviderLangChainMapper {
    String ERR_UNEXPECTED_ROLE = "unexpected role '%s'";
    String ERR_ROLE_MSG_TYPE_MISMATCH = "role and message instance are not matching, role: '%s', instance: '%s'";

    LlmProviderLangChainMapper INSTANCE = Mappers.getMapper(LlmProviderLangChainMapper.class);

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

    default List<ChatMessage> mapMessages(ChatCompletionRequest request) {
        return request.messages().stream().map(this::toChatMessage).toList();
    }

    default Optional<ErrorMessage> getGeminiErrorObject(@NonNull Throwable throwable, Logger log) {
        String message = throwable.getMessage();
        var openBraceIndex = message.indexOf('{');
        if (openBraceIndex >= 0) {
            String jsonPart = message.substring(openBraceIndex); // Extract JSON part
            try {
                var geminiError = JsonUtils.readValue(jsonPart, GeminiErrorObject.class);
                return geminiError.toErrorMessage();
            } catch (UncheckedIOException e) {
                log.warn("failed to parse Gemini error message", e);
                return Optional.empty();
            }
        }

        return Optional.empty();
    }

}
