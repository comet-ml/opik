package com.comet.opik.domain.llm;

import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest.Builder;
import dev.langchain4j.model.openai.internal.chat.Content;
import dev.langchain4j.model.openai.internal.chat.ContentType;
import dev.langchain4j.model.openai.internal.chat.ImageUrl;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.UserMessage;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class MessageContentNormalizer {

    private MessageContentNormalizer() {
    }

    public static ChatCompletionRequest normalizeRequest(ChatCompletionRequest request,
            boolean allowStructuredContent) {
        if (allowStructuredContent) {
            return request;
        }

        if (request.messages() == null || request.messages().isEmpty()) {
            return request;
        }

        boolean needsNormalization = request.messages().stream()
                .anyMatch(message -> message instanceof UserMessage userMessage
                        && !(userMessage.content() instanceof String));

        if (!needsNormalization) {
            return request;
        }

        List<Message> normalizedMessages = new ArrayList<>(request.messages().size());
        for (Message message : request.messages()) {
            if (message instanceof UserMessage userMessage) {
                normalizedMessages.add(normalizeUserMessage(userMessage));
            } else {
                normalizedMessages.add(message);
            }
        }

        Builder builder = ChatCompletionRequest.builder().from(request);
        builder.messages(normalizedMessages);
        return builder.build();
    }

    public static String flattenContent(Object rawContent) {
        if (rawContent instanceof String str) {
            return str;
        }

        if (rawContent instanceof List<?> list) {
            StringBuilder builder = new StringBuilder();
            for (Object item : list) {
                if (item instanceof Content content) {
                    builder.append(renderContent(content));
                }
            }
            return builder.toString().trim();
        }

        return String.valueOf(rawContent);
    }

    private static Message normalizeUserMessage(UserMessage userMessage) {
        String flattened = flattenContent(userMessage.content());
        UserMessage.Builder builder = UserMessage.builder();

        if (userMessage.name() != null) {
            builder.name(userMessage.name());
        }

        builder.content(flattened);
        return builder.build();
    }

    private static String renderContent(Content content) {
        ContentType type = content.type();
        if (type == null) {
            return "";
        }

        String normalized = type.name().toLowerCase(Locale.ENGLISH);
        return switch (normalized) {
            case "text" -> content.text() == null ? "" : content.text();
            case "image_url" -> renderImagePlaceholder(content.imageUrl());
            default -> "";
        };
    }

    private static String renderImagePlaceholder(ImageUrl imageUrl) {
        if (imageUrl == null || imageUrl.getUrl() == null || imageUrl.getUrl().isBlank()) {
            return "";
        }

        return String.format("<<<image>>>%s<<</image>>>", imageUrl.getUrl());
    }
}
