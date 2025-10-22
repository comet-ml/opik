package com.comet.opik.domain.llm;

import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.Content;
import dev.langchain4j.model.openai.internal.chat.ImageUrl;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.tika.utils.StringUtils;

import java.util.ArrayList;
import java.util.List;

@Slf4j
@UtilityClass
public class MessageContentNormalizer {

    public static final String IMAGE_PLACEHOLDER_START = "<<<image>>>";
    public static final String IMAGE_PLACEHOLDER_END = "<<</image>>>";

    public ChatCompletionRequest normalizeRequest(@NonNull ChatCompletionRequest request) {
        boolean allowStructuredContent = ModelCapabilities.supportsVision(request.model());
        return normalizeRequest(request, allowStructuredContent);
    }

    private ChatCompletionRequest normalizeRequest(ChatCompletionRequest request, boolean allowStructuredContent) {
        if (allowStructuredContent) {
            return request;
        }

        if (CollectionUtils.isEmpty(request.messages())) {
            return request;
        }

        var needsNormalization = request.messages().stream()
                .anyMatch(message -> message instanceof UserMessage userMessage
                        && !(userMessage.content() instanceof String));

        if (!needsNormalization) {
            return request;
        }

        var normalizedMessages = new ArrayList<Message>(request.messages().size());
        for (var message : request.messages()) {
            if (message instanceof UserMessage userMessage) {
                normalizedMessages.add(normalizeUserMessage(userMessage));
            } else {
                normalizedMessages.add(message);
            }
        }

        var builder = ChatCompletionRequest.builder().from(request);
        builder.messages(normalizedMessages);
        return builder.build();
    }

    public static String flattenContent(@NonNull Object rawContent) {
        if (rawContent instanceof String str) {
            return str;
        }

        if (rawContent instanceof List<?> list) {
            var builder = new StringBuilder();
            for (var item : list) {
                if (item instanceof Content content) {
                    builder.append(renderContent(content));
                }
            }
            return builder.toString().trim();
        }

        return String.valueOf(rawContent);
    }

    private Message normalizeUserMessage(UserMessage userMessage) {
        var flattened = flattenContent(userMessage.content());
        var builder = UserMessage.builder();

        if (userMessage.name() != null) {
            builder.name(userMessage.name());
        }

        builder.content(flattened);
        return builder.build();
    }

    private String renderContent(Content content) {
        var type = content.type();
        if (type == null) {
            return "";
        }

        var normalized = type.name().toLowerCase();
        return switch (normalized) {
            case "text" -> StringUtils.isBlank(content.text()) ? "" : content.text();
            case "image_url" -> renderImagePlaceholder(content.imageUrl());
            default -> {
                log.warn("Skipping unknown content type during normalization: '{}'", normalized);
                yield "";
            }
        };
    }

    private String renderImagePlaceholder(ImageUrl imageUrl) {
        if (imageUrl == null || StringUtils.isBlank(imageUrl.getUrl())) {
            return "";
        }

        return String.format("%s%s%s", IMAGE_PLACEHOLDER_START, imageUrl.getUrl(), IMAGE_PLACEHOLDER_END);
    }
}
