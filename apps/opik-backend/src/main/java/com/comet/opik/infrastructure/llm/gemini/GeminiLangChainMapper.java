package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.domain.llm.langchain4j.OpikContent;
import com.comet.opik.domain.llm.langchain4j.OpikUserMessage;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import com.comet.opik.infrastructure.llm.VideoMimeTypeUtils;
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

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Gemini-specific mapper that converts VIDEO_URL to ImageContent.
 * Gemini's API treats videos as images, so we convert video URLs to image content.
 */
@Mapper
public interface GeminiLangChainMapper {

    Logger log = LoggerFactory.getLogger(GeminiLangChainMapper.class);

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
                    if (mimeType == null && !VideoMimeTypeUtils.hasVideoFileExtension(videoUrl)) {
                        mimeType = VideoMimeTypeUtils.detectMimeTypeFromHttpHead(videoUrl);
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
            case AUDIO_URL -> throw new BadRequestException("Audio URL content not yet supported for Gemini");
            case AUDIO -> throw new BadRequestException("Audio content not yet supported for Gemini");
            case FILE -> throw new BadRequestException("File content not yet supported for Gemini");
        };
    }
}
