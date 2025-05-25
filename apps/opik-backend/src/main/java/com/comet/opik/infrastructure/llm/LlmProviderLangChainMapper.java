package com.comet.opik.infrastructure.llm;

import com.comet.opik.infrastructure.llm.gemini.GeminiErrorObject;
import com.comet.opik.infrastructure.llm.openai.OpenAiErrorMessage;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterErrorMessage;
import com.comet.opik.utils.JsonUtils;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.openai.internal.chat.AssistantMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionChoice;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.Role;
import dev.langchain4j.model.openai.internal.chat.SystemMessage;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import dev.langchain4j.model.openai.internal.shared.Usage;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.dropwizard.util.Throwables;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
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
    String CANNOT_BE_NULL_OR_EMPTY = "Message content cannot be null or empty";

    default ChatMessage toChatMessage(@NonNull Message message) {
        if (!List.of(Role.ASSISTANT, Role.USER, Role.SYSTEM).contains(message.role())) {
            throw new BadRequestException(ERR_UNEXPECTED_ROLE.formatted(message.role()));
        }

        switch (message.role()) {
            case ASSISTANT -> {
                if (message instanceof AssistantMessage assistantMessage) {
                    validateMessageContent(assistantMessage.content());
                    return AiMessage.from(assistantMessage.content());
                }
            }
            case USER -> {
                if (message instanceof UserMessage userMessage) {
                    validateMessageContent(userMessage.content().toString());
                    return dev.langchain4j.data.message.UserMessage.from(userMessage.content().toString());
                }
            }
            case SYSTEM -> {
                if (message instanceof SystemMessage systemMessage) {
                    validateMessageContent(systemMessage.content());
                    return dev.langchain4j.data.message.SystemMessage.from(systemMessage.content());
                }
            }
        }

        throw new BadRequestException(ERR_ROLE_MSG_TYPE_MISMATCH.formatted(message.role(),
                message.getClass().getSimpleName()));
    }

    private void validateMessageContent(String content) {
        if (StringUtils.isBlank(content)) {
            throw new BadRequestException(CANNOT_BE_NULL_OR_EMPTY);
        }
    }

    @Mapping(expression = "java(request.model())", target = "model")
    @Mapping(source = "response", target = "choices", qualifiedByName = "mapToChoices")
    @Mapping(source = "response", target = "usage", qualifiedByName = "mapToUsage")
    @Mapping(source = "response", target = "id", qualifiedByName = "mapToId")
    ChatCompletionResponse toChatCompletionResponse(
            @NonNull ChatCompletionRequest request, @NonNull ChatResponse response);

    @Named("mapToChoices")
    default List<ChatCompletionChoice> mapToChoices(@NonNull ChatResponse response) {
        return List.of(ChatCompletionChoice.builder()
                .message(AssistantMessage.builder().content(response.aiMessage().text()).build())
                .build());
    }

    @Named("mapToId")
    default String mapToId(@NonNull ChatResponse response) {
        return Optional.ofNullable(response.metadata())
                .map(ChatResponseMetadata::id)
                .orElse(null);
    }

    @Named("mapToUsage")
    default Usage mapToUsage(@NonNull ChatResponse response) {
        return Usage.builder()
                .promptTokens(response.tokenUsage().inputTokenCount())
                .completionTokens(response.tokenUsage().outputTokenCount())
                .totalTokens(response.tokenUsage().totalTokenCount())
                .build();
    }

    default List<ChatMessage> mapMessages(ChatCompletionRequest request) {
        return request.messages().stream().map(this::toChatMessage).toList();
    }

    default Optional<ErrorMessage> getGeminiErrorObject(@NonNull Throwable throwable, @NonNull Logger log) {
        return getErrorMessage(throwable, log, GeminiErrorObject.class);
    }

    private <E, T extends LlmProviderError<E>> Optional<ErrorMessage> getErrorMessage(Throwable throwable, Logger log,
            Class<T> errorType) {
        Optional<Throwable> llmProviderError = Throwables.findThrowableInChain(this::findError, throwable);

        String failToGetErrorMessage = "failed to parse %s message".formatted(errorType.getSimpleName());

        if (llmProviderError.isEmpty()) {
            log.warn(failToGetErrorMessage, throwable);
            return Optional.empty();
        }

        String message = llmProviderError.get().getMessage();
        int openBraceIndex = message.indexOf('{');
        String jsonPart = message.substring(openBraceIndex);

        Optional<T> error = parseError(log, jsonPart, errorType);
        return error.map(LlmProviderError::toErrorMessage);
    }

    private <E, T extends LlmProviderError<E>> Optional<T> parseError(Logger log, String jsonPart, Class<T> errorType) {

        String failToGetErrorMessage = "failed to parse %s message".formatted(errorType.getSimpleName());

        try {
            var error = JsonUtils.readValue(jsonPart, errorType);
            if (error.error() == null) {
                return Optional.empty();
            }
            return Optional.of(error);
        } catch (UncheckedIOException e) {
            log.warn(failToGetErrorMessage, e);
            return Optional.empty();
        }
    }

    private boolean findError(Throwable t) {
        return t.getMessage() != null && t.getMessage().contains("{");
    }

    default Optional<ErrorMessage> getErrorObject(@NonNull Throwable throwable, @NonNull Logger log) {

        Optional<ErrorMessage> errorMessage = getErrorMessage(throwable, log, OpenRouterErrorMessage.class);

        if (errorMessage.isPresent()) {
            return errorMessage;
        }

        return getErrorMessage(throwable, log, OpenAiErrorMessage.class);
    }
}
