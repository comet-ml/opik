package com.comet.opik.infrastructure.llm;

import com.comet.opik.domain.llm.langchain4j.OpikContent;
import com.comet.opik.domain.llm.langchain4j.OpikUserMessage;
import com.comet.opik.infrastructure.llm.customllm.CustomLlmErrorMessage;
import com.comet.opik.infrastructure.llm.gemini.GeminiErrorObject;
import com.comet.opik.infrastructure.llm.openai.OpenAiErrorMessage;
import com.comet.opik.infrastructure.llm.openrouter.OpenRouterErrorMessage;
import com.comet.opik.utils.JsonUtils;
import dev.langchain4j.data.message.AiMessage;
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
import java.net.HttpURLConnection;
import java.net.URI;
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
                    var videoBuilder = Video.builder()
                            .url(URI.create(opikContent.videoUrl().url()));
                    // Use explicit mimeType if provided, otherwise auto-detect from URL
                    String mimeType = opikContent.videoUrl().mimeType();
                    if (mimeType == null) {
                        mimeType = detectMimeTypeFromUrl(opikContent.videoUrl().url());
                    }
                    if (mimeType != null) {
                        videoBuilder.mimeType(mimeType);
                    }
                    yield new VideoContent(videoBuilder.build());
                }
                throw new BadRequestException("Video URL is null");
            }
            case AUDIO -> throw new BadRequestException("Audio content not yet supported in conversion");
            case FILE -> throw new BadRequestException("File content not yet supported in conversion");
        };
    }

    // Common video file extensions mapped to MIME types
    java.util.Map<String, String> VIDEO_MIME_TYPES = java.util.Map.ofEntries(
            java.util.Map.entry("mp4", "video/mp4"),
            java.util.Map.entry("webm", "video/webm"),
            java.util.Map.entry("ogg", "video/ogg"),
            java.util.Map.entry("ogv", "video/ogg"),
            java.util.Map.entry("avi", "video/x-msvideo"),
            java.util.Map.entry("mov", "video/quicktime"),
            java.util.Map.entry("wmv", "video/x-ms-wmv"),
            java.util.Map.entry("flv", "video/x-flv"),
            java.util.Map.entry("mkv", "video/x-matroska"),
            java.util.Map.entry("m4v", "video/x-m4v"),
            java.util.Map.entry("3gp", "video/3gpp"),
            java.util.Map.entry("3g2", "video/3gpp2"));

    /**
     * Detect MIME type from URL. First tries to detect from file extension,
     * and only makes an HTTP HEAD request if the extension is not recognized.
     *
     * @param url the URL to check
     * @return the MIME type, or null if detection fails
     */
    private String detectMimeTypeFromUrl(String url) {
        Logger log = LoggerFactory.getLogger(LlmProviderLangChainMapper.class);

        // First, try to detect from file extension (fast, no network call)
        String mimeFromExtension = detectMimeTypeFromExtension(url);
        if (mimeFromExtension != null) {
            log.debug("Detected MIME type '{}' from file extension: '{}'", mimeFromExtension,
                    url.substring(0, Math.min(50, url.length())));
            return mimeFromExtension;
        }

        // No recognizable extension, fall back to HTTP HEAD request
        return detectMimeTypeFromHttpHead(url);
    }

    /**
     * Try to detect MIME type from the file extension in the URL path.
     */
    private String detectMimeTypeFromExtension(String url) {
        try {
            var uri = URI.create(url);
            String path = uri.getPath();
            if (path == null || path.isBlank()) {
                return null;
            }

            // Get the last part of the path (filename)
            int lastSlash = path.lastIndexOf('/');
            String filename = lastSlash >= 0 ? path.substring(lastSlash + 1) : path;

            // Extract extension
            int lastDot = filename.lastIndexOf('.');
            if (lastDot < 0 || lastDot == filename.length() - 1) {
                return null;
            }

            String extension = filename.substring(lastDot + 1).toLowerCase();
            return VIDEO_MIME_TYPES.get(extension);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Detect MIME type by making an HTTP HEAD request to read the Content-Type header.
     */
    private String detectMimeTypeFromHttpHead(String url) {
        Logger log = LoggerFactory.getLogger(LlmProviderLangChainMapper.class);
        try {
            var uri = URI.create(url);
            if (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme())) {
                return null;
            }

            log.debug("Making HEAD request to detect MIME type for URL without extension: '{}'",
                    url.substring(0, Math.min(50, url.length())));

            HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            connection.connect();

            String contentType = connection.getContentType();
            connection.disconnect();

            if (contentType != null && !contentType.isBlank()) {
                // Content-Type may include charset, e.g., "video/mp4; charset=utf-8"
                // Extract just the MIME type part
                int semicolonIndex = contentType.indexOf(';');
                if (semicolonIndex > 0) {
                    contentType = contentType.substring(0, semicolonIndex).trim();
                }
                log.debug("Detected MIME type '{}' from HTTP HEAD: '{}'", contentType,
                        url.substring(0, Math.min(50, url.length())));
                return contentType;
            }
        } catch (Exception e) {
            log.debug("Failed to detect MIME type from HTTP HEAD: '{}', error: '{}'",
                    url.substring(0, Math.min(50, url.length())), e.getMessage());
        }
        return null;
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
