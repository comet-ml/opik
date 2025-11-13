package com.comet.opik.domain.llm;

import com.comet.opik.utils.JsonUtils;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.Content;
import dev.langchain4j.model.openai.internal.chat.ImageUrl;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

@Slf4j
@UtilityClass
public class MessageContentNormalizer {

    public static final String IMAGE_PLACEHOLDER_START = "<<<image>>>";
    public static final String IMAGE_PLACEHOLDER_END = "<<</image>>>";
    public static final String VIDEO_PLACEHOLDER_START = "<<<video>>>";
    public static final String VIDEO_PLACEHOLDER_END = "<<</video>>>";
    private static final Pattern MEDIA_PLACEHOLDER_PATTERN = Pattern.compile(
            "<<<(image|video)>>>(.*?)<<</(image|video)>>>",
            Pattern.DOTALL);

    public ChatCompletionRequest normalizeRequest(@NonNull ChatCompletionRequest request) {
        boolean allowStructuredContent = ModelCapabilities.supportsVision(request.model())
                || ModelCapabilities.supportsVideo(request.model());
        return normalizeRequest(request, allowStructuredContent);
    }

    private ChatCompletionRequest normalizeRequest(ChatCompletionRequest request, boolean allowStructuredContent) {
        if (CollectionUtils.isEmpty(request.messages())) {
            return request;
        }

        if (allowStructuredContent) {
            // For multimodal models: expand string content with media tags to structured content
            return expandMediaPlaceholders(request);
        }

        // For non-vision models: flatten structured content to string
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

    /**
     * For multimodal models: converts string content with image/video placeholders to structured content.
     * Example: "text\n<<<image>>>url<<</image>>>" becomes [{type: "text", text: "text"}, {type: "image_url", image_url: {url: "url"}}]
     */
    private ChatCompletionRequest expandMediaPlaceholders(ChatCompletionRequest request) {
        var needsExpansion = request.messages().stream()
                .anyMatch(message -> message instanceof UserMessage userMessage
                        && userMessage.content() instanceof String content
                        && containsMediaPlaceholder(content));

        if (!needsExpansion) {
            return request;
        }

        var expandedMessages = new ArrayList<Message>(request.messages().size());
        for (var message : request.messages()) {
            if (message instanceof UserMessage userMessage && userMessage.content() instanceof String content) {
                expandedMessages.add(expandUserMessage(userMessage, content));
            } else {
                expandedMessages.add(message);
            }
        }

        var builder = ChatCompletionRequest.builder().from(request);
        builder.messages(expandedMessages);
        return builder.build();
    }

    private boolean containsMediaPlaceholder(String content) {
        return content.contains(IMAGE_PLACEHOLDER_START) || content.contains(VIDEO_PLACEHOLDER_START);
    }

    private UserMessage expandUserMessage(UserMessage userMessage, String content) {
        var matcher = MEDIA_PLACEHOLDER_PATTERN.matcher(content);

        if (!matcher.find()) {
            return userMessage;
        }

        matcher.reset();

        List<Object> contentList = new ArrayList<>();
        var lastIndex = 0;

        while (matcher.find()) {
            if (matcher.start() > lastIndex) {
                var textSegment = content.substring(lastIndex, matcher.start());
                appendTextContent(contentList, textSegment);
            }

            var mediaType = matcher.group(1);
            var rawValue = matcher.group(2).trim();
            if (!rawValue.isEmpty()) {
                var unescaped = StringEscapeUtils.unescapeHtml4(rawValue);
                // Frontend mustache templates sometimes escape URLs; unescape first so downstream models
                // receive the exact media URL/base64 payload that the author supplied.
                var mediaContent = createMediaContent(mediaType, unescaped);
                if (mediaContent != null) {
                    contentList.add(mediaContent);
                }
            }

            lastIndex = matcher.end();
        }

        if (lastIndex < content.length()) {
            var trailingText = content.substring(lastIndex);
            appendTextContent(contentList, trailingText);
        }

        var builder = UserMessage.builder();
        if (userMessage.name() != null) {
            builder.name(userMessage.name());
        }
        @SuppressWarnings("unchecked")
        List<Content> structuredContent = (List<Content>) (List<?>) contentList;
        builder.content(structuredContent);

        return builder.build();
    }

    /**
     * Appends text content to the content list if the text is not blank.
     */
    private void appendTextContent(List<Object> contentList, String textSegment) {
        if (StringUtils.isNotBlank(textSegment)) {
            contentList.add(Map.of(
                    "type", "text",
                    "text", textSegment));
        }
    }

    private Object createMediaContent(String mediaType, String rawValue) {
        if ("image".equalsIgnoreCase(mediaType)) {
            return Map.of(
                    "type", "image_url",
                    "image_url", Map.of("url", rawValue));
        }

        if ("video".equalsIgnoreCase(mediaType)) {
            var trimmed = StringUtils.trimToEmpty(rawValue);
            if (looksLikeJson(trimmed)) {
                Object parsed = tryParseJson(trimmed);
                if (parsed != null) {
                    return parsed;
                }
            }

            return Map.of(
                    "type", "video_url",
                    "video_url", Map.of("url", rawValue));
        }

        log.warn("Unsupported media type placeholder encountered: '{}'", mediaType);
        return null;
    }

    private boolean looksLikeJson(String value) {
        var trimmed = StringUtils.trimToEmpty(value);
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private Object tryParseJson(String rawValue) {
        try {
            return JsonUtils.readValue(rawValue, Object.class);
        } catch (RuntimeException exception) {
            log.warn("Failed to parse media placeholder as JSON: {}", rawValue, exception);
            return null;
        }
    }

    /**
     * Flatten LangChain4j Content list back to string with video/image tags.
     * Used when converting ChatRequest to ChatCompletionRequest.
     */
    public static String flattenContent(@NonNull List<dev.langchain4j.data.message.Content> contents) {
        var builder = new StringBuilder();
        for (var content : contents) {
            if (content instanceof dev.langchain4j.data.message.TextContent textContent) {
                builder.append(textContent.text());
            } else if (content instanceof dev.langchain4j.data.message.VideoContent videoContent) {
                var video = videoContent.video();
                if (video == null) {
                    continue;
                }

                if (video.url() != null) {
                    builder.append(renderVideoPlaceholder(video.url().toString()));
                    continue;
                }

                var inlineVideoPayload = toInlineVideoPayload(video.base64Data(), video.mimeType());
                if (StringUtils.isNotBlank(inlineVideoPayload)) {
                    builder.append(renderVideoPlaceholder(inlineVideoPayload));
                }
            } else if (content instanceof dev.langchain4j.data.message.ImageContent imageContent) {
                var image = imageContent.image();
                if (image != null && image.url() != null) {
                    builder.append(renderImagePlaceholder(image.url().toString()));
                }
            }
        }
        return builder.toString().trim();
    }

    public static String flattenContent(@NonNull Object rawContent) {
        if (rawContent instanceof String str) {
            return str;
        }

        if (rawContent instanceof List<?> list) {
            return flattenList(list).trim();
        }

        if (rawContent instanceof Map<?, ?> map) {
            return renderMapContent(map);
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

    private static String flattenList(List<?> list) {
        var builder = new StringBuilder();
        for (var item : list) {
            if (item instanceof Content content) {
                builder.append(renderContent(content));
            } else if (item instanceof Map<?, ?> map) {
                builder.append(renderMapContent(map));
            } else if (item instanceof List<?> nested) {
                builder.append(flattenList(nested));
            } else if (item != null) {
                builder.append(item);
            }
        }
        return builder.toString();
    }

    private static String renderMapContent(Map<?, ?> map) {
        Object typeObj = map.get("type");
        if (!(typeObj instanceof String type)) {
            return "";
        }

        var normalized = type.toLowerCase();
        return switch (normalized) {
            case "text" -> {
                Object text = map.get("text");
                yield text instanceof String ? (String) text : "";
            }
            case "image_url" -> {
                Object imageValue = map.get("image_url");
                if (imageValue instanceof Map<?, ?> imageMap) {
                    Object url = imageMap.get("url");
                    yield url != null ? renderImagePlaceholder(url.toString()) : "";
                }
                yield "";
            }
            case "video_url" -> {
                Object videoValue = map.get("video_url");
                if (videoValue instanceof Map<?, ?> videoMap) {
                    Object url = videoMap.get("url");
                    yield url != null ? renderVideoPlaceholder(url.toString()) : "";
                }
                yield "";
            }
            case "file" -> {
                Object fileValue = map.get("file");
                if (fileValue instanceof Map<?, ?> fileMap) {
                    Object fileId = fileMap.get("file_id");
                    if (fileId instanceof String fileIdString && StringUtils.isNotBlank(fileIdString)) {
                        yield renderVideoPlaceholder(fileIdString);
                    }
                    Object fileData = fileMap.get("file_data");
                    if (fileData instanceof String fileDataString && StringUtils.isNotBlank(fileDataString)) {
                        yield renderVideoPlaceholder(fileDataString);
                    }
                }
                yield "";
            }
            default -> "";
        };
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
        if (imageUrl == null) {
            return "";
        }
        return renderImagePlaceholder(imageUrl.getUrl());
    }

    private static String renderImagePlaceholder(String imageUrl) {
        if (StringUtils.isBlank(imageUrl)) {
            return "";
        }
        return String.format("%s%s%s", IMAGE_PLACEHOLDER_START, imageUrl, IMAGE_PLACEHOLDER_END);
    }

    private static String renderVideoPlaceholder(String videoUrl) {
        if (StringUtils.isBlank(videoUrl)) {
            return "";
        }
        return String.format("%s%s%s", VIDEO_PLACEHOLDER_START, videoUrl, VIDEO_PLACEHOLDER_END);
    }

    private String toInlineVideoPayload(String base64Data, String mimeType) {
        if (StringUtils.isBlank(base64Data)) {
            return "";
        }

        var safeMimeType = StringUtils.defaultIfBlank(mimeType, "video/mp4");
        Map<String, Object> filePayload = new LinkedHashMap<>();
        filePayload.put("file_data", base64Data);
        filePayload.put("format", safeMimeType);

        Map<String, Object> payload = Map.of(
                "type", "file",
                "file", filePayload);

        try {
            return JsonUtils.writeValueAsString(payload);
        } catch (UncheckedIOException exception) {
            log.warn("Failed to serialize inline video payload", exception);
            return "";
        }
    }
}
