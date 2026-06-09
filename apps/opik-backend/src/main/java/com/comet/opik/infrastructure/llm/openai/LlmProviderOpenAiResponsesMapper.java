package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.domain.llm.MessageContentNormalizer;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.internal.chat.AssistantMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionChoice;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.chat.Delta;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.shared.Usage;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;
import java.util.Optional;

/**
 * Translates between the OpenAI Chat-Completions wire DTOs (langchain4j openai-internal) and
 * langchain4j's provider-agnostic ChatRequest/ChatResponse. Used by {@link LlmProviderOpenAiResponses}
 * to bridge the proxy contract (Chat-Completions in/out) onto the Responses-API-backed ChatModel.
 * <br/>
 * Scope: text-only system/user/assistant messages, basic sampling parameters, token usage, and finish
 * reason. Tool calling, structured response formats, multimodal content, and streaming chunks are
 * intentionally out of scope for the first pass and should be added incrementally.
 */
@lombok.experimental.UtilityClass
class LlmProviderOpenAiResponsesMapper {

    // OpenAI Chat-Completions wire-format role token for assistant messages. langchain4j's Role enum
    // toString() yields the uppercase Java name; clients expect a lowercase per the OpenAI API spec.
    private static final String ASSISTANT_ROLE_WIRE_VALUE = "assistant";

    ChatRequest toChatRequest(@NonNull ChatCompletionRequest request) {
        var builder = ChatRequest.builder()
                .modelName(request.model())
                .messages(toLangChainMessages(request));

        Optional.ofNullable(request.temperature()).ifPresent(builder::temperature);
        Optional.ofNullable(request.topP()).ifPresent(builder::topP);
        Optional.ofNullable(request.frequencyPenalty()).ifPresent(builder::frequencyPenalty);
        Optional.ofNullable(request.presencePenalty()).ifPresent(builder::presencePenalty);
        Optional.ofNullable(resolveMaxOutputTokens(request)).ifPresent(builder::maxOutputTokens);

        if (CollectionUtils.isNotEmpty(request.stop())) {
            builder.stopSequences(request.stop());
        }

        return builder.build();
    }

    ChatCompletionResponse toChatCompletionResponse(@NonNull ChatResponse response,
            @NonNull ChatCompletionRequest originalRequest) {
        var metadata = response.metadata();
        return ChatCompletionResponse.builder()
                .id(metadata.id())
                .model(metadata.modelName() != null ? metadata.modelName() : originalRequest.model())
                .choices(List.of(toChoice(response)))
                .usage(toUsage(response.tokenUsage()))
                .build();
    }

    /**
     * Streaming chunk carrying a text delta. Mirrors OpenAI's intermediate SSE chunk shape:
     * {@code {choices:[{index:0, delta:{role:"assistant", content:"<partial>"}}]}}.
     * Role is included on every chunk for simplicity — OpenAI clients tolerate it on non-initial
     * chunks, and tracking "is this the first chunk?" state isn't worth the bookkeeping.
     */
    ChatCompletionResponse toPartialChunk(@NonNull String partial, @NonNull ChatCompletionRequest originalRequest) {
        return ChatCompletionResponse.builder()
                .model(originalRequest.model())
                .choices(List.of(ChatCompletionChoice.builder()
                        .index(0)
                        .delta(Delta.builder()
                                .role(ASSISTANT_ROLE_WIRE_VALUE)
                                .content(partial)
                                .build())
                        .build()))
                .build();
    }

    /**
     * Final streaming chunk: empty delta, populated {@code finish_reason}, and token usage when
     * provided by the upstream response. Mirrors OpenAI's terminating chunk shape; sending usage
     * here (rather than on a dedicated post-final chunk) is a deliberate simplification — clients
     * relying on {@code stream_options.include_usage} still see the data, just inlined.
     */
    ChatCompletionResponse toFinalChunk(@NonNull ChatResponse response,
            @NonNull ChatCompletionRequest originalRequest) {
        var metadata = response.metadata();
        return ChatCompletionResponse.builder()
                .id(metadata.id())
                .model(metadata.modelName() != null ? metadata.modelName() : originalRequest.model())
                .choices(List.of(ChatCompletionChoice.builder()
                        .index(0)
                        .delta(Delta.builder().build())
                        .finishReason(toFinishReasonString(response.finishReason()))
                        .build()))
                .usage(toUsage(response.tokenUsage()))
                .build();
    }

    private List<ChatMessage> toLangChainMessages(@NonNull ChatCompletionRequest request) {
        return request.messages().stream().map(LlmProviderOpenAiResponsesMapper::toLangChainMessage).toList();
    }

    private ChatMessage toLangChainMessage(@NonNull Message message) {
        return switch (message) {
            case dev.langchain4j.model.openai.internal.chat.SystemMessage sys -> SystemMessage.from(sys.content());
            case dev.langchain4j.model.openai.internal.chat.UserMessage user -> UserMessage.from(
                    MessageContentNormalizer.flattenContent(user.content()));
            case AssistantMessage assistant -> AiMessage.from(assistant.content());
            default -> throw new BadRequestException(
                    "Unsupported message role for OpenAI Responses proxy: " + message.role());
        };
    }

    private ChatCompletionChoice toChoice(@NonNull ChatResponse response) {
        return ChatCompletionChoice.builder()
                .index(0)
                .message(AssistantMessage.builder()
                        .content(response.aiMessage().text())
                        .build())
                .finishReason(toFinishReasonString(response.finishReason()))
                .build();
    }

    private Usage toUsage(TokenUsage tokenUsage) {
        if (tokenUsage == null) {
            return null;
        }
        return Usage.builder()
                .promptTokens(tokenUsage.inputTokenCount())
                .completionTokens(tokenUsage.outputTokenCount())
                .totalTokens(tokenUsage.totalTokenCount())
                .build();
    }

    private String toFinishReasonString(FinishReason finishReason) {
        if (finishReason == null) {
            return null;
        }
        return switch (finishReason) {
            case STOP -> "stop";
            case LENGTH -> "length";
            case TOOL_EXECUTION -> "tool_calls";
            case CONTENT_FILTER -> "content_filter";
            default -> "other";
        };
    }

    private Integer resolveMaxOutputTokens(@NonNull ChatCompletionRequest request) {
        if (request.maxCompletionTokens() != null) {
            return request.maxCompletionTokens();
        }
        return request.maxTokens();
    }
}
