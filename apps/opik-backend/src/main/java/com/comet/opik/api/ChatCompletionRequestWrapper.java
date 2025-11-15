package com.comet.opik.api;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.ResponseFormat;
import dev.langchain4j.model.openai.internal.chat.Tool;
import dev.langchain4j.model.openai.internal.chat.ToolChoice;
import dev.langchain4j.model.openai.internal.shared.StreamOptions;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * Wrapper for ChatCompletionRequest that includes extra_body parameter.
 * This allows the playground to send provider-specific parameters per request.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@Schema(description = "Chat completion request with support for extra_body parameters")
public record ChatCompletionRequestWrapper(
        @JsonProperty("model") String model,
        @JsonProperty("messages") List<Message> messages,
        @JsonProperty("temperature") Double temperature,
        @JsonProperty("top_p") Double topP,
        @JsonProperty("n") Integer n,
        @JsonProperty("stream") Boolean stream,
        @JsonProperty("stream_options") StreamOptions streamOptions,
        @JsonProperty("stop") Object stop,
        @JsonProperty("max_tokens") Integer maxTokens,
        @JsonProperty("max_completion_tokens") Integer maxCompletionTokens,
        @JsonProperty("presence_penalty") Double presencePenalty,
        @JsonProperty("frequency_penalty") Double frequencyPenalty,
        @JsonProperty("logit_bias") Map<String, Integer> logitBias,
        @JsonProperty("user") String user,
        @JsonProperty("response_format") ResponseFormat responseFormat,
        @JsonProperty("seed") Integer seed,
        @JsonProperty("tools") List<Tool> tools,
        @JsonProperty("tool_choice") ToolChoice toolChoice,
        @JsonProperty("parallel_tool_calls") Boolean parallelToolCalls,
        @JsonProperty("store") Boolean store,
        @JsonProperty("metadata") Map<String, String> metadata,
        @JsonProperty("reasoning_effort") String reasoningEffort,
        @JsonProperty("service_tier") String serviceTier,
        @JsonProperty("functions") List<Object> functions,
        @JsonProperty("function_call") Object functionCall,
        @JsonProperty("extra_body") @Schema(description = "Additional provider-specific parameters sent with the request") Map<String, Object> extraBody) {

    /**
     * Convert wrapper to LangChain4j's ChatCompletionRequest
     */
    public ChatCompletionRequest toChatCompletionRequest() {
        var builder = ChatCompletionRequest.builder()
                .model(model)
                .messages(messages)
                .temperature(temperature)
                .topP(topP)
                .n(n)
                .stream(stream)
                .maxTokens(maxTokens)
                .maxCompletionTokens(maxCompletionTokens)
                .presencePenalty(presencePenalty)
                .frequencyPenalty(frequencyPenalty)
                .logitBias(logitBias)
                .user(user)
                .responseFormat(responseFormat)
                .seed(seed)
                .tools(tools)
                .toolChoice(toolChoice)
                .parallelToolCalls(parallelToolCalls)
                .store(store)
                .metadata(metadata)
                .reasoningEffort(reasoningEffort)
                .serviceTier(serviceTier);

        // Handle optional streamOptions if present
        if (streamOptions != null) {
            builder.streamOptions(streamOptions);
        }

        // Note: stop, functions, and functionCall are omitted as they require specific types
        // that would complicate deserialization. These can be added if needed in the future.

        return builder.build();
    }
}
