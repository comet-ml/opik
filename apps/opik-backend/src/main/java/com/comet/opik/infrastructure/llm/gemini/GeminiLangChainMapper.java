package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.domain.llm.langchain4j.OpikContent;
import com.comet.opik.domain.llm.langchain4j.OpikUserMessage;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.Message;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import org.mapstruct.Mapper;
import org.mapstruct.factory.Mappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.HttpURLConnection;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Gemini-specific mapper that converts VIDEO_URL to ImageContent.
 * Gemini's API treats videos as images, so we convert video URLs to image content.
 */
@Mapper
public interface GeminiLangChainMapper {

    GeminiLangChainMapper INSTANCE = Mappers.getMapper(GeminiLangChainMapper.class);

    /**
     * Map messages for Gemini, converting VIDEO_URL to ImageContent.
     */
    default List<ChatMessage> mapMessagesForGemini(@NonNull ChatCompletionRequest request) {
        return request.messages().stream()
                .map(this::toChatMessageForGemini)
                .toList();
    }

    /**
     * Convert Message to ChatMessage, with Gemini-specific handling for OpikUserMessage.
     */
    default ChatMessage toChatMessageForGemini(@NonNull Message message) {
        // Handle OpikUserMessage specially for Gemini
        if (message instanceof OpikUserMessage opikUserMessage) {
            return convertOpikUserMessageForGemini(opikUserMessage);
        }
        // For other messages, use the standard mapper
        return LlmProviderLangChainMapper.INSTANCE.toChatMessage(message);
    }

    /**
     * Convert OpikUserMessage to UserMessage with VIDEO_URL -> ImageContent conversion.
     */
    default UserMessage convertOpikUserMessageForGemini(OpikUserMessage opikUserMessage) {
        if (opikUserMessage.content() instanceof String stringContent) {
            if (stringContent == null || stringContent.isBlank()) {
                throw new BadRequestException("Message content cannot be null or empty");
            }
            return UserMessage.from(stringContent);
        } else if (opikUserMessage.content() instanceof List<?> contentList) {
            // Convert OpikContent list to public API Content list with VIDEO_URL -> ImageContent
            List<Content> publicApiContents = new ArrayList<>();
            for (Object item : contentList) {
                if (item instanceof OpikContent opikContent) {
                    publicApiContents.add(convertOpikContentForGemini(opikContent));
                }
            }
            return UserMessage.from(publicApiContents);
        }
        throw new BadRequestException("Invalid OpikUserMessage content type");
    }

    /**
     * Convert OpikContent to public API Content for Gemini.
     * Gemini treats videos as images, so VIDEO_URL is converted to ImageContent.
     */
    default Content convertOpikContentForGemini(OpikContent opikContent) {
        Logger log = LoggerFactory.getLogger(GeminiLangChainMapper.class);
        return switch (opikContent.type()) {
            case TEXT -> TextContent.from(opikContent.text());
            case IMAGE_URL -> {
                if (opikContent.imageUrl() != null) {
                    yield ImageContent.from(opikContent.imageUrl().getUrl());
                }
                throw new BadRequestException("Image URL is null");
            }
            case VIDEO_URL -> {
                // Gemini treats videos as images - convert VIDEO_URL to ImageContent
                if (opikContent.videoUrl() != null) {
                    String videoUrl = opikContent.videoUrl().url();
                    var imageBuilder = Image.builder()
                            .url(URI.create(videoUrl));

                    // Use explicit mimeType if provided
                    String mimeType = opikContent.videoUrl().mimeType();

                    // Only detect MIME type if not provided AND URL has no file extension
                    // (LangChain4j can detect MIME type from extensions automatically)
                    if (mimeType == null && !hasVideoFileExtension(videoUrl)) {
                        mimeType = detectMimeTypeFromHttpHead(videoUrl);
                    }

                    if (mimeType != null) {
                        imageBuilder.mimeType(mimeType);
                        log.debug("Set mimeType '{}' for video URL: '{}'", mimeType,
                                videoUrl.substring(0, Math.min(60, videoUrl.length())));
                    }
                    yield ImageContent.from(imageBuilder.build());
                }
                throw new BadRequestException("Video URL is null");
            }
            case AUDIO -> throw new BadRequestException("Audio content not yet supported for Gemini");
            case FILE -> throw new BadRequestException("File content not yet supported for Gemini");
        };
    }

    // Common video file extensions that LangChain4j can detect automatically
    java.util.Set<String> VIDEO_EXTENSIONS = java.util.Set.of(
            "mp4", "webm", "ogg", "ogv", "avi", "mov", "wmv", "flv", "mkv", "m4v", "3gp", "3g2");

    /**
     * Check if the URL has a recognizable video file extension.
     * If it does, LangChain4j will detect the MIME type automatically.
     */
    private boolean hasVideoFileExtension(String url) {
        try {
            String path = URI.create(url).getPath();
            if (path == null || path.isBlank()) {
                return false;
            }
            String extension = com.google.common.io.Files.getFileExtension(path).toLowerCase();
            return VIDEO_EXTENSIONS.contains(extension);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Detect MIME type by making an HTTP HEAD request to read the Content-Type header.
     * Only called for URLs without file extensions.
     */
    private String detectMimeTypeFromHttpHead(String url) {
        Logger log = LoggerFactory.getLogger(GeminiLangChainMapper.class);
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
}
