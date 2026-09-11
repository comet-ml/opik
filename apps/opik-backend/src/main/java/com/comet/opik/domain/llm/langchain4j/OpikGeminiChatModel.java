package com.comet.opik.domain.llm.langchain4j;

import dev.langchain4j.data.image.Image;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Custom Gemini chat model that converts VideoContent to ImageContent.
 * Gemini's API treats videos as images, so we convert video URLs before sending to the model.
 */
@Slf4j
@RequiredArgsConstructor
public class OpikGeminiChatModel implements ChatModel {

    private final @NonNull ChatModel delegate;

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        // Convert messages: if any UserMessage has VideoContent, convert to ImageContent
        List<ChatMessage> convertedMessages = convertMessagesForGemini(chatRequest.messages());

        // Create new request with converted messages
        ChatRequest convertedRequest = ChatRequest.builder()
                .messages(convertedMessages)
                .parameters(chatRequest.parameters())
                .build();

        return delegate.chat(convertedRequest);
    }

    /**
     * Convert messages for Gemini: VideoContent -> ImageContent
     */
    private List<ChatMessage> convertMessagesForGemini(List<ChatMessage> messages) {
        return messages.stream()
                .map(this::convertMessageForGemini)
                .collect(Collectors.toList());
    }

    /**
     * Convert a single message, handling VideoContent in UserMessages.
     */
    private ChatMessage convertMessageForGemini(ChatMessage message) {
        // Only UserMessage can have VideoContent
        if (!(message instanceof UserMessage)) {
            return message;
        }

        UserMessage userMessage = (UserMessage) message;

        // Simple text-only message - no conversion needed
        if (userMessage.hasSingleText()) {
            return message;
        }

        // Multi-content message - check if it has video
        boolean hasVideo = userMessage.contents().stream()
                .anyMatch(content -> content instanceof VideoContent);

        if (!hasVideo) {
            // No video, return as-is
            return message;
        }

        // Has video - convert VideoContent to ImageContent
        List<Content> convertedContents = new ArrayList<>();
        for (Content content : userMessage.contents()) {
            if (content instanceof VideoContent videoContent) {
                // Gemini treats videos as images. A Video carries either a url or inline base64, never
                // both: MinIO-staged attachments arrive as base64, so reading url() here would throw.
                var video = videoContent.video();
                var imageBuilder = Image.builder();
                if (video.url() != null) {
                    var url = video.url().toString();
                    log.debug("Converting VideoContent to ImageContent for Gemini: {}",
                            url.substring(0, Math.min(50, url.length())));
                    imageBuilder.url(video.url());
                } else if (StringUtils.isNotEmpty(video.base64Data())) {
                    log.debug("Converting inline VideoContent to ImageContent for Gemini");
                    imageBuilder.base64Data(video.base64Data());
                } else {
                    // Nothing convertible; pass it through rather than build an empty image.
                    convertedContents.add(content);
                    continue;
                }
                if (video.mimeType() != null) {
                    imageBuilder.mimeType(video.mimeType());
                }
                convertedContents.add(ImageContent.from(imageBuilder.build()));
            } else {
                // Audio, files and text are left untouched — dropping them would silently lose input
                convertedContents.add(content);
            }
        }

        return UserMessage.from(convertedContents);
    }
}
