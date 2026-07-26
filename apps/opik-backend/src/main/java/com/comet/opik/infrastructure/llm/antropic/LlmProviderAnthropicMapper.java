package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.domain.llm.MessageContentNormalizer;
import com.comet.opik.domain.llm.langchain4j.OpikContent;
import com.comet.opik.domain.llm.langchain4j.OpikUserMessage;
import dev.langchain4j.model.anthropic.internal.api.AnthropicContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageRequest;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageResponse;
import dev.langchain4j.model.anthropic.internal.api.AnthropicImageContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicMessage;
import dev.langchain4j.model.anthropic.internal.api.AnthropicMessageContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicRole;
import dev.langchain4j.model.anthropic.internal.api.AnthropicTextContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicUsage;
import dev.langchain4j.model.openai.internal.chat.AssistantMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionChoice;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.Role;
import dev.langchain4j.model.openai.internal.chat.SystemMessage;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import dev.langchain4j.model.openai.internal.shared.Usage;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;
import org.mapstruct.factory.Mappers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Objects;

@Mapper
interface LlmProviderAnthropicMapper {
    LlmProviderAnthropicMapper INSTANCE = Mappers.getMapper(LlmProviderAnthropicMapper.class);

    Logger LOG = LoggerFactory.getLogger(LlmProviderAnthropicMapper.class);

    // Anthropic requires max_tokens; default applied when callers don't set it (Playground default
    // config, SDK without an explicit cap). 4096 mirrors langchain4j's default on the judge path.
    int DEFAULT_MAX_COMPLETION_TOKENS = 4096;

    @Mapping(source = "response", target = "choices", qualifiedByName = "mapToChoices")
    @Mapping(source = "usage", target = "usage", qualifiedByName = "mapToUsage")
    ChatCompletionResponse toResponse(@NonNull AnthropicCreateMessageResponse response);

    @Mapping(source = "content", target = "message")
    @Mapping(source = "response.stopReason", target = "finishReason")
    ChatCompletionChoice toChoice(@NonNull AnthropicContent content, @NonNull AnthropicCreateMessageResponse response);

    @Mapping(source = "text", target = "content")
    AssistantMessage toAssistantMessage(@NonNull AnthropicContent content);

    @Mapping(expression = "java(request.model())", target = "model")
    @Mapping(expression = "java(Boolean.TRUE.equals(request.stream()))", target = "stream")
    @Mapping(source = "request", target = "temperature", qualifiedByName = "resolveTemperature")
    @Mapping(source = "request", target = "topP", qualifiedByName = "resolveTopP")
    @Mapping(expression = "java(request.stop())", target = "stopSequences")
    @Mapping(source = "request", target = "maxTokens", qualifiedByName = "resolveMaxTokens")
    @Mapping(source = "request", target = "messages", qualifiedByName = "mapToMessages")
    @Mapping(source = "request", target = "system", qualifiedByName = "mapToSystemMessages")
    AnthropicCreateMessageRequest toCreateMessageRequest(@NonNull ChatCompletionRequest request);

    @Named("resolveTemperature")
    default Double resolveTemperature(@NonNull ChatCompletionRequest request) {
        return samplingParamsAllowed(request) ? request.temperature() : null;
    }

    @Named("resolveTopP")
    default Double resolveTopP(@NonNull ChatCompletionRequest request) {
        if (!samplingParamsAllowed(request)) {
            return null;
        }
        // Anthropic recommends against sending temperature and top_p together; temperature wins when both are set.
        return request.temperature() != null ? null : request.topP();
    }

    /**
     * Anthropic rejects sampling params (temperature/top_p) with a 400 for adaptive-thinking models
     * (claude-sonnet-5, claude-opus-4-7/4-8) and whenever extended thinking is enabled for the request.
     * Gate them server-side so API-created requests — which bypass the FE sanitizer from OPIK-6244 — don't
     * fail. Mirrors the judge-path logic in {@code AnthropicClientGenerator}.
     */
    private boolean samplingParamsAllowed(ChatCompletionRequest request) {
        return AnthropicModelName.supportsSamplingParams(request.model()) && !thinkingEnabled(request);
    }

    /**
     * Extended thinking counts as enabled only when the request's {@code custom_parameters.thinking.type} is an
     * explicit, non-blank value other than {@code "disabled"} — so {@code "enabled"}, {@code "adaptive"}, and any
     * future type gate sampling params off, while a missing/blank type (or absent block) leaves them untouched.
     */
    private boolean thinkingEnabled(ChatCompletionRequest request) {
        if (request.customParameters() == null
                || !(request.customParameters().get("thinking") instanceof Map<?, ?> thinking)) {
            return false;
        }
        return thinking.get("type") instanceof String type
                && StringUtils.isNotBlank(type)
                && !"disabled".equals(type);
    }

    @Named("resolveMaxTokens")
    default Integer resolveMaxTokens(@NonNull ChatCompletionRequest request) {
        if (request.maxCompletionTokens() != null) {
            return request.maxCompletionTokens();
        }
        LOG.info("Anthropic request for model '{}' has no maxCompletionTokens; defaulting to {}",
                request.model(), DEFAULT_MAX_COMPLETION_TOKENS);
        return DEFAULT_MAX_COMPLETION_TOKENS;
    }

    @Named("mapToChoices")
    default List<ChatCompletionChoice> mapToChoices(@NonNull AnthropicCreateMessageResponse response) {
        if (response.content == null || response.content.isEmpty()) {
            return List.of();
        }
        return response.content.stream().map(content -> toChoice(content, response)).toList();
    }

    @Named("mapToUsage")
    default Usage mapToUsage(AnthropicUsage usage) {
        if (usage == null) {
            return null;
        }

        return Usage.builder()
                .promptTokens(usage.inputTokens)
                .completionTokens(usage.outputTokens)
                .totalTokens(usage.inputTokens + usage.outputTokens)
                .build();
    }

    @Named("mapToMessages")
    default List<AnthropicMessage> mapToMessages(@NonNull ChatCompletionRequest request) {
        return request.messages().stream()
                .filter(message -> List.of(Role.ASSISTANT, Role.USER).contains(message.role()))
                .map(this::mapToAnthropicMessage).toList();
    }

    @Named("mapToSystemMessages")
    default List<AnthropicTextContent> mapToSystemMessages(@NonNull ChatCompletionRequest request) {
        return request.messages().stream()
                .filter(message -> message.role() == Role.SYSTEM)
                .map(this::mapToSystemMessage).toList();
    }

    default AnthropicMessage mapToAnthropicMessage(@NonNull Message message) {
        return switch (message) {
            case AssistantMessage assistantMessage -> AnthropicMessage.builder()
                    .role(AnthropicRole.ASSISTANT)
                    .content(List.of(new AnthropicTextContent(assistantMessage.content())))
                    .build();
            case OpikUserMessage opikUserMessage -> AnthropicMessage.builder()
                    .role(AnthropicRole.USER)
                    .content(toAnthropicMessageContents(opikUserMessage.content()))
                    .build();
            case UserMessage userMessage -> AnthropicMessage.builder()
                    .role(AnthropicRole.USER)
                    .content(List.of(toAnthropicMessageContent(userMessage.content())))
                    .build();
            default -> throw new BadRequestException("unexpected message role: " + message.role());
        };
    }

    /**
     * Convert OpikUserMessage content to Anthropic message content list.
     * Handles both string content and structured multimodal content (text, images, etc.).
     */
    default List<AnthropicMessageContent> toAnthropicMessageContents(@NonNull Object rawContent) {
        // If it's a string, return a single text content (if not empty)
        if (rawContent instanceof String stringContent) {
            if (StringUtils.isNotBlank(stringContent)) {
                return List.of(new AnthropicTextContent(stringContent));
            }
            // Empty string - return empty list (Anthropic will reject empty text blocks)
            return List.of();
        }

        // If it's a list of OpikContent, convert each item
        if (rawContent instanceof List<?> contentList) {
            return contentList.stream()
                    .filter(OpikContent.class::isInstance)
                    .map(OpikContent.class::cast)
                    .map(opikContent -> switch (opikContent.type()) {
                        case TEXT -> new AnthropicTextContent(opikContent.text());
                        case IMAGE_URL -> AnthropicImageContent.fromUrl(opikContent.imageUrl().getUrl());
                        case VIDEO_URL -> new AnthropicTextContent("[Video: " + opikContent.videoUrl().url() + "]");
                        default -> null;
                    })
                    .filter(Objects::nonNull)
                    .toList();
        }

        // Fallback: flatten to string
        var content = MessageContentNormalizer.flattenContent(rawContent);
        if (StringUtils.isNotBlank(content)) {
            return List.of(new AnthropicTextContent(content));
        }
        return List.of();
    }

    default AnthropicMessageContent toAnthropicMessageContent(@NonNull Object rawContent) {
        var content = MessageContentNormalizer.flattenContent(rawContent);
        return new AnthropicTextContent(content);
    }

    default AnthropicTextContent mapToSystemMessage(@NonNull Message message) {
        if (message.role() != Role.SYSTEM) {
            throw new BadRequestException("expecting only system role, got: " + message.role());
        }

        return new AnthropicTextContent(((SystemMessage) message).content());
    }
}
