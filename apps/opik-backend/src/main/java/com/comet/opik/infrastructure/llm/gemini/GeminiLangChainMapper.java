package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.domain.llm.langchain4j.OpikContent;
import com.comet.opik.domain.llm.langchain4j.OpikUserMessage;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
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
                    yield ImageContent.from(opikContent.videoUrl().url());
                }
                throw new BadRequestException("Video URL is null");
            }
            case AUDIO -> throw new BadRequestException("Audio content not yet supported for Gemini");
            case FILE -> throw new BadRequestException("File content not yet supported for Gemini");
        };
    }
}
