package com.comet.opik.domain.llm;

import com.comet.opik.domain.llm.langchain4j.OpikUserMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.Content;
import dev.langchain4j.model.openai.internal.chat.ContentType;
import dev.langchain4j.model.openai.internal.chat.ImageUrl;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

@Slf4j
@UtilityClass
public class MessageContentNormalizer {

    public static final String IMAGE_PLACEHOLDER_START = "<<<image>>>";
    public static final String IMAGE_PLACEHOLDER_END = "<<</image>>>";
    private static final Pattern IMAGE_PLACEHOLDER_PATTERN = Pattern.compile(
            Pattern.quote(IMAGE_PLACEHOLDER_START) + "(.*?)" + Pattern.quote(IMAGE_PLACEHOLDER_END),
            Pattern.DOTALL);

    public ChatCompletionRequest normalizeRequest(@NonNull ChatCompletionRequest request) {
        boolean allowStructuredContent = ModelCapabilities.supportsVision(request.model());
        return normalizeRequest(request, allowStructuredContent);
    }

    private ChatCompletionRequest normalizeRequest(ChatCompletionRequest request, boolean allowStructuredContent) {
        if (CollectionUtils.isEmpty(request.messages())) {
            return request;
        }

        if (allowStructuredContent) {
            // For vision-capable models: expand string content with image tags to structured content
            return expandImagePlaceholders(request);
        }

        // For non-vision models: flatten structured content to string
        var needsNormalization = request.messages().stream()
                .anyMatch(message -> (message instanceof UserMessage userMessage
                        && !(userMessage.content() instanceof String))
                        || (message instanceof OpikUserMessage opikUserMessage
                                && !(opikUserMessage.content() instanceof String)));

        if (!needsNormalization) {
            return request;
        }

        var normalizedMessages = new ArrayList<Message>(request.messages().size());
        for (var message : request.messages()) {
            if (message instanceof OpikUserMessage opikUserMessage) {
                normalizedMessages.add(normalizeOpikUserMessage(opikUserMessage));
            } else if (message instanceof UserMessage userMessage) {
                normalizedMessages.add(normalizeUserMessage(userMessage));
            } else {
                normalizedMessages.add(message);
            }
        }

        var builder = ChatCompletionRequest.builder().from(request);
        builder.messages(normalizedMessages);
        return builder.build();
    }

    /**
     * For vision-capable models: converts string content with image placeholders to structured content.
     * Example: "text\n<<<image>>>url<<</image>>>" becomes [{type: "text", text: "text"}, {type: "image_url", image_url: {url: "url"}}]
     */
    private ChatCompletionRequest expandImagePlaceholders(ChatCompletionRequest request) {
        var needsExpansion = request.messages().stream()
                .anyMatch(message -> (message instanceof UserMessage userMessage
                        && userMessage.content() instanceof String content
                        && content.contains(IMAGE_PLACEHOLDER_START))
                        || (message instanceof OpikUserMessage opikUserMessage
                                && opikUserMessage.content() instanceof String opikContent
                                && opikContent.contains(IMAGE_PLACEHOLDER_START)));

        if (!needsExpansion) {
            return request;
        }

        var expandedMessages = new ArrayList<Message>(request.messages().size());
        for (var message : request.messages()) {
            if (message instanceof OpikUserMessage opikUserMessage
                    && opikUserMessage.content() instanceof String content) {
                expandedMessages.add(expandOpikUserMessage(opikUserMessage, content));
            } else if (message instanceof UserMessage userMessage && userMessage.content() instanceof String content) {
                expandedMessages.add(expandUserMessage(userMessage, content));
            } else {
                expandedMessages.add(message);
            }
        }

        var builder = ChatCompletionRequest.builder().from(request);
        builder.messages(expandedMessages);
        return builder.build();
    }

    /**
     * Expands a UserMessage with string content containing image placeholders to structured content.
     * Parses image placeholders and creates a list of Content objects (text and image_url).
     */
    private UserMessage expandUserMessage(UserMessage userMessage, String content) {
        var matcher = IMAGE_PLACEHOLDER_PATTERN.matcher(content);

        if (!matcher.find()) {
            // No image placeholders found, return as-is
            return userMessage;
        }

        // Reset matcher to start from beginning
        matcher.reset();

        var contentList = new ArrayList<Content>();
        var lastIndex = 0;

        while (matcher.find()) {
            // Add text content before the image placeholder
            if (matcher.start() > lastIndex) {
                var textSegment = content.substring(lastIndex, matcher.start());
                appendTextContent(contentList, textSegment);
            }

            // Extract and add image URL
            var url = matcher.group(1).trim();
            if (!url.isEmpty()) {
                // Unescape HTML entities for backward compatibility with URLs that were escaped by Mustache templates on the frontend
                // RECOMMENDED: Use {{{variable}}} (triple braces) or {{&variable}} in Mustache templates on the frontend
                // to prevent HTML escaping of URLs before sending them to the backend
                var unescapedUrl = StringEscapeUtils.unescapeHtml4(url);
                contentList.add(Content.builder()
                        .type(ContentType.IMAGE_URL)
                        .imageUrl(ImageUrl.builder().url(unescapedUrl).build())
                        .build());
            }

            lastIndex = matcher.end();
        }

        // Add any remaining text after the last image placeholder
        if (lastIndex < content.length()) {
            var trailingText = content.substring(lastIndex);
            appendTextContent(contentList, trailingText);
        }

        // Build the expanded UserMessage
        var builder = UserMessage.builder();
        if (userMessage.name() != null) {
            builder.name(userMessage.name());
        }
        builder.content(contentList);

        return builder.build();
    }

    /**
     * Appends text content to the content list if the text is not blank.
     */
    private void appendTextContent(List<Content> contentList, String textSegment) {
        if (StringUtils.isNotBlank(textSegment)) {
            contentList.add(Content.builder()
                    .type(ContentType.TEXT)
                    .text(textSegment)
                    .build());
        }
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

    /**
     * Normalize OpikUserMessage by flattening structured content to string.
     */
    private Message normalizeOpikUserMessage(OpikUserMessage opikUserMessage) {
        // If content is already a string, return as-is
        if (opikUserMessage.content() instanceof String) {
            return opikUserMessage;
        }

        // If content is a list, flatten it to string
        var flattened = flattenContent(opikUserMessage.content());
        return OpikUserMessage.builder()
                .name(opikUserMessage.name())
                .content(flattened)
                .build();
    }

    /**
     * Expand OpikUserMessage with string content containing image placeholders to structured content.
     */
    private OpikUserMessage expandOpikUserMessage(OpikUserMessage opikUserMessage, String content) {
        var matcher = IMAGE_PLACEHOLDER_PATTERN.matcher(content);

        if (!matcher.find()) {
            // No image placeholders found, return as-is
            return opikUserMessage;
        }

        // Reset matcher to start from beginning
        matcher.reset();

        var builder = OpikUserMessage.builder();
        if (opikUserMessage.name() != null) {
            builder.name(opikUserMessage.name());
        }

        var lastIndex = 0;

        while (matcher.find()) {
            // Add text content before the image placeholder
            if (matcher.start() > lastIndex) {
                var textSegment = content.substring(lastIndex, matcher.start());
                if (StringUtils.isNotBlank(textSegment)) {
                    builder.addText(textSegment);
                }
            }

            // Extract and add image URL
            var url = matcher.group(1).trim();
            if (!url.isEmpty()) {
                var unescapedUrl = StringEscapeUtils.unescapeHtml4(url);
                builder.addImageUrl(unescapedUrl);
            }

            lastIndex = matcher.end();
        }

        // Add any remaining text after the last image placeholder
        if (lastIndex < content.length()) {
            var trailingText = content.substring(lastIndex);
            if (StringUtils.isNotBlank(trailingText)) {
                builder.addText(trailingText);
            }
        }

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
