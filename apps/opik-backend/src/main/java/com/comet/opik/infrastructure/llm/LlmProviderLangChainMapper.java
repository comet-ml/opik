package com.comet.opik.infrastructure.llm;

import com.comet.opik.domain.llm.langchain4j.OpikContent;
import com.comet.opik.domain.llm.langchain4j.OpikUserMessage;
import com.comet.opik.infrastructure.llm.customllm.CustomLlmErrorMessage;
import com.comet.opik.infrastructure.llm.gemini.GeminiErrorObject;
import com.comet.opik.infrastructure.llm.openai.OpenAiErrorMessage;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterErrorMessage;
import com.comet.opik.utils.JsonUtils;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.data.video.Video;
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
import org.slf4j.LoggerFactory;

import java.io.UncheckedIOException;
import java.net.URI;
import java.util.List;
import java.util.Optional;

@Mapper
public interface LlmProviderLangChainMapper {
    Logger log = LoggerFactory.getLogger(LlmProviderLangChainMapper.class);

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
                } else if (message instanceof OpikUserMessage opikUserMessage) {
                    return convertOpikUserMessage(opikUserMessage);
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

    /**
     * Convert OpikUserMessage to public API UserMessage.
     * OpikUserMessage supports multimodal content (text, images, videos, etc.)
     * and its content can be either a String or a List<OpikContent>.
     */
    private dev.langchain4j.data.message.UserMessage convertOpikUserMessage(OpikUserMessage opikUserMessage) {
        if (opikUserMessage.content() instanceof String stringContent) {
            validateMessageContent(stringContent);
            return dev.langchain4j.data.message.UserMessage.from(stringContent);
        } else if (opikUserMessage.content() instanceof List<?> contentList) {
            // Convert OpikContent list to public API Content list
            List<Content> publicApiContents = new java.util.ArrayList<>();
            for (Object item : contentList) {
                if (item instanceof OpikContent opikContent) {
                    publicApiContents.add(convertOpikContent(opikContent));
                }
            }
            return dev.langchain4j.data.message.UserMessage.from(publicApiContents);
        }
        throw new BadRequestException("Invalid OpikUserMessage content type");
    }

    /**
     * Convert OpikContent to public API Content.
     */
    private Content convertOpikContent(OpikContent opikContent) {
        return switch (opikContent.type()) {
            case TEXT -> TextContent.from(opikContent.text());
            case IMAGE_URL -> {
                if (opikContent.imageUrl() != null) {
                    yield ImageContent.from(opikContent.imageUrl().getUrl());
                }
                throw new BadRequestException("Image URL is null");
            }
            case VIDEO_URL -> {
                if (opikContent.videoUrl() != null) {
                    String videoUrl = opikContent.videoUrl().url();
                    var videoBuilder = Video.builder()
                            .url(URI.create(videoUrl));

                    // Use explicit mimeType if provided
                    String mimeType = opikContent.videoUrl().mimeType();

                    // Only detect MIME type if not provided AND URL has no file extension
                    // (LangChain4j can detect MIME type from extensions automatically)
                    if (mimeType == null && !VideoMimeTypeUtils.hasVideoFileExtension(videoUrl)) {
                        mimeType = VideoMimeTypeUtils.detectMimeTypeFromHttpHead(videoUrl);
                    }

                    if (mimeType != null) {
                        videoBuilder.mimeType(mimeType);
                        log.debug("Set mimeType '{}' for video URL: '{}'", mimeType,
                                videoUrl.substring(0, Math.min(60, videoUrl.length())));
                    }
                    yield new VideoContent(videoBuilder.build());
                }
                throw new BadRequestException("Video URL is null");
            }
            case AUDIO_URL -> {
                if (opikContent.audioUrl() != null) {
                    yield AudioContent.from(opikContent.audioUrl().url());
                }
                throw new BadRequestException("Audio URL is null");
            }
            case AUDIO -> throw new BadRequestException("Audio content not yet supported in conversion");
            case FILE -> throw new BadRequestException("File content not yet supported in conversion");
        };
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

    default Optional<ErrorMessage> getCustomLlmErrorObject(@NonNull Throwable throwable, @NonNull Logger log) {
        return getErrorMessage(throwable, log, CustomLlmErrorMessage.class);
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
