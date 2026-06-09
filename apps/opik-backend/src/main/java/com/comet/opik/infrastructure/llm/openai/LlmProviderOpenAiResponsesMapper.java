package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.domain.llm.MessageContentNormalizer;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.internal.JsonSchemaElementJsonUtils;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.internal.chat.AssistantMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionChoice;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.chat.Delta;
import dev.langchain4j.model.openai.internal.chat.Function;
import dev.langchain4j.model.openai.internal.chat.FunctionCall;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.Tool;
import dev.langchain4j.model.openai.internal.chat.ToolCall;
import dev.langchain4j.model.openai.internal.chat.ToolMessage;
import dev.langchain4j.model.openai.internal.chat.ToolType;
import dev.langchain4j.model.openai.internal.shared.Usage;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Translates between the OpenAI Chat-Completions wire DTOs (langchain4j openai-internal) and
 * langchain4j's provider-agnostic ChatRequest/ChatResponse. Used by {@link LlmProviderOpenAiResponses}
 * to bridge the proxy contract (Chat-Completions in/out) onto the Responses-API-backed ChatModel.
 * <br/>
 * Scope: text-only system/user/assistant messages, tool calling (tool specs, tool_choice,
 * assistant tool_calls, tool result resume), structured response formats (json_object and
 * json_schema), basic sampling parameters, token usage, and finish reason. Multimodal content and
 * per-token streaming of tool-call argument deltas are intentionally out of scope and should be
 * added incrementally.
 */
@lombok.experimental.UtilityClass
@Slf4j
class LlmProviderOpenAiResponsesMapper {

    // OpenAI Chat-Completions wire-format role token for assistant messages. langchain4j's Role enum
    // toString() yields the uppercase Java name; clients expect a lowercase per the OpenAI API spec.
    private static final String ASSISTANT_ROLE_WIRE_VALUE = "assistant";

    // langchain4j's openai-internal JsonSchema has no public accessors — its fields are read back
    // via a Jackson roundtrip. This dedicated mapper is intentionally minimal: it just needs to
    // honour @JsonProperty annotations on the source class, which the default ObjectMapper does.
    private static final ObjectMapper RESPONSE_FORMAT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

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

        if (CollectionUtils.isNotEmpty(request.tools())) {
            builder.toolSpecifications(toToolSpecifications(request.tools()));
        }
        Optional.ofNullable(toToolChoice(request.toolChoice())).ifPresent(builder::toolChoice);
        Optional.ofNullable(toResponseFormat(request.responseFormat())).ifPresent(builder::responseFormat);

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
            case AssistantMessage assistant -> toAiMessage(assistant);
            case ToolMessage tool -> new ToolExecutionResultMessage(tool.toolCallId(), null, tool.content());
            default -> throw new BadRequestException(
                    "Unsupported message role for OpenAI Responses proxy: " + message.role());
        };
    }

    /**
     * Translates an incoming assistant message to {@link AiMessage}. When the client resumes a tool
     * loop, the assistant turn carries {@code tool_calls} with no/empty content; langchain4j models
     * that as an AiMessage whose {@code toolExecutionRequests} list is populated.
     */
    private AiMessage toAiMessage(@NonNull AssistantMessage assistant) {
        if (CollectionUtils.isNotEmpty(assistant.toolCalls())) {
            var requests = assistant.toolCalls().stream()
                    .map(LlmProviderOpenAiResponsesMapper::toToolExecutionRequest)
                    .toList();
            return AiMessage.builder()
                    .text(assistant.content())
                    .toolExecutionRequests(requests)
                    .build();
        }
        return AiMessage.from(assistant.content());
    }

    private ToolExecutionRequest toToolExecutionRequest(@NonNull ToolCall toolCall) {
        var fn = toolCall.function();
        return ToolExecutionRequest.builder()
                .id(toolCall.id())
                .name(fn != null ? fn.name() : null)
                .arguments(fn != null ? fn.arguments() : null)
                .build();
    }

    private ChatCompletionChoice toChoice(@NonNull ChatResponse response) {
        var aiMessage = response.aiMessage();
        var assistantBuilder = AssistantMessage.builder().content(aiMessage.text());
        if (CollectionUtils.isNotEmpty(aiMessage.toolExecutionRequests())) {
            assistantBuilder.toolCalls(toToolCalls(aiMessage.toolExecutionRequests()));
        }
        return ChatCompletionChoice.builder()
                .index(0)
                .message(assistantBuilder.build())
                .finishReason(toFinishReasonString(response.finishReason()))
                .build();
    }

    private List<ToolCall> toToolCalls(@NonNull List<ToolExecutionRequest> requests) {
        return requests.stream().map(LlmProviderOpenAiResponsesMapper::toToolCall).toList();
    }

    private ToolCall toToolCall(@NonNull ToolExecutionRequest request) {
        return ToolCall.builder()
                .id(request.id())
                .type(ToolType.FUNCTION)
                .function(FunctionCall.builder()
                        .name(request.name())
                        .arguments(request.arguments())
                        .build())
                .build();
    }

    private List<ToolSpecification> toToolSpecifications(@NonNull List<Tool> tools) {
        return tools.stream()
                .map(Tool::function)
                .filter(java.util.Objects::nonNull)
                .map(LlmProviderOpenAiResponsesMapper::toToolSpecification)
                .toList();
    }

    private ToolSpecification toToolSpecification(@NonNull Function function) {
        var builder = ToolSpecification.builder().name(function.name());
        Optional.ofNullable(function.description()).ifPresent(builder::description);
        Optional.ofNullable(function.strict()).ifPresent(builder::strict);
        Optional.ofNullable(function.parameters())
                .map(LlmProviderOpenAiResponsesMapper::toJsonObjectSchema)
                .ifPresent(builder::parameters);
        return builder.build();
    }

    private JsonObjectSchema toJsonObjectSchema(@NonNull Map<String, Object> parameters) {
        var element = JsonSchemaElementJsonUtils.fromMap(parameters);
        if (element instanceof JsonObjectSchema objectSchema) {
            return objectSchema;
        }
        // Tool parameters are required to be an object schema per the OpenAI spec; non-object root
        // schemas indicate a malformed request from the caller.
        throw new BadRequestException(
                "OpenAI tool parameters must be a JSON Schema object; got: " + element.getClass().getSimpleName());
    }

    /**
     * Maps OpenAI's {@code tool_choice} to langchain4j's {@link ToolChoice}. Supports the three
     * string forms ("auto", "required", "none"). The named-function variant
     * ({@code {"type":"function","function":{"name":"foo"}}}) is logged and falls back to AUTO,
     * since langchain4j's enum has no equivalent — the model is still allowed to pick the named
     * function, but other tools aren't excluded. Returns {@code null} when {@code toolChoice} is
     * absent so the builder uses langchain4j's default.
     */
    private ToolChoice toToolChoice(Object toolChoice) {
        if (toolChoice == null) {
            return null;
        }
        if (toolChoice instanceof String s) {
            return switch (s.toLowerCase(java.util.Locale.ROOT)) {
                case "auto" -> ToolChoice.AUTO;
                case "required" -> ToolChoice.REQUIRED;
                // langchain4j's enum nominally has NONE, but selecting it would mean "do not call any
                // tool" — same semantics OpenAI gives. Pass through.
                case "none" -> ToolChoice.NONE;
                default -> {
                    log.warn("Unknown OpenAI tool_choice string '{}', falling back to AUTO", s);
                    yield ToolChoice.AUTO;
                }
            };
        }
        log.info("Named-function tool_choice not supported by langchain4j ToolChoice enum; "
                + "falling back to AUTO. tool_choice={}", toolChoice);
        return ToolChoice.AUTO;
    }

    /**
     * Maps OpenAI's {@code response_format} to langchain4j's {@link ResponseFormat}. {@code text}
     * returns {@code null} (langchain4j's default). {@code json_object} → loose JSON mode.
     * {@code json_schema} → strict structured-output mode with name + root schema. The {@code strict}
     * flag from the request is NOT propagated — langchain4j's per-request {@link JsonSchema} has no
     * strict slot; strict mode is configured at {@code OpenAiOfficialResponsesChatModel} build time
     * via {@code .strictJsonSchema(...)}. Returns {@code null} when {@code responseFormat} is absent.
     */
    private ResponseFormat toResponseFormat(dev.langchain4j.model.openai.internal.chat.ResponseFormat inputFormat) {
        if (inputFormat == null || inputFormat.type() == null) {
            return null;
        }
        return switch (inputFormat.type()) {
            case TEXT -> null;
            case JSON_OBJECT -> ResponseFormat.JSON;
            case JSON_SCHEMA -> toJsonSchemaResponseFormat(inputFormat.jsonSchema());
        };
    }

    private ResponseFormat toJsonSchemaResponseFormat(
            dev.langchain4j.model.openai.internal.chat.JsonSchema jsonSchema) {
        if (jsonSchema == null) {
            // json_schema variant without a schema body falls back to loose JSON mode rather than
            // throwing — defensive against malformed clients that send the type alone.
            return ResponseFormat.JSON;
        }
        // The openai-internal JsonSchema is opaque (no public accessors), so re-emit it through
        // Jackson to read the underlying fields. Cheap roundtrip on a tiny POJO.
        Map<String, Object> raw = RESPONSE_FORMAT_MAPPER.convertValue(jsonSchema, MAP_TYPE);
        String name = (String) raw.get("name");
        Object schemaObj = raw.get("schema");
        if (!(schemaObj instanceof Map<?, ?>)) {
            return ResponseFormat.JSON;
        }
        @SuppressWarnings("unchecked")
        var schemaMap = (Map<String, Object>) schemaObj;
        var rootSchema = toJsonObjectSchema(schemaMap);
        return ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(JsonSchema.builder()
                        .name(name)
                        .rootElement(rootSchema)
                        .build())
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