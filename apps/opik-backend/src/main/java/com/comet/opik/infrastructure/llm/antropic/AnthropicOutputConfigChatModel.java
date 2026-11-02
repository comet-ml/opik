package com.comet.opik.infrastructure.llm.antropic;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.anthropic.internal.api.AnthropicFormat;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.NonNull;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import static com.comet.opik.infrastructure.llm.antropic.AnthropicEffort.FORMAT;
import static com.comet.opik.infrastructure.llm.antropic.AnthropicEffort.OUTPUT_CONFIG;

// langchain4j writes a JSON-schema response format as output_config.format, while the rule's output_config (effort)
// can only ride customParameters, which it writes as a second top-level output_config. Anthropic keeps one of the
// two, so the judge's schema was silently dropped. A request with a schema is therefore sent through a model whose
// single output_config holds both, with the response format taken off the request so langchain4j adds no other.
class AnthropicOutputConfigChatModel implements ChatModel {

    private final Map<?, ?> ruleOutputConfig;
    private final Function<Map<String, Object>, ChatModel> modelWithCustomParameters;
    private final Map<Map<?, ?>, ChatModel> modelsByOutputConfig = new ConcurrentHashMap<>();

    AnthropicOutputConfigChatModel(@NonNull Map<?, ?> ruleOutputConfig,
            @NonNull Function<Map<String, Object>, ChatModel> modelWithCustomParameters) {
        this.ruleOutputConfig = ruleOutputConfig;
        this.modelWithCustomParameters = modelWithCustomParameters;
    }

    @Override
    public ChatResponse chat(@NonNull ChatRequest chatRequest) {
        var format = toAnthropicFormat(chatRequest.responseFormat());
        if (format == null) {
            return modelFor(ruleOutputConfig).chat(chatRequest);
        }
        var outputConfig = new LinkedHashMap<Object, Object>(ruleOutputConfig);
        outputConfig.put(FORMAT, format);
        return modelFor(outputConfig).chat(withoutResponseFormat(chatRequest));
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return modelFor(ruleOutputConfig).defaultRequestParameters();
    }

    @Override
    public ModelProvider provider() {
        return modelFor(ruleOutputConfig).provider();
    }

    @Override
    public Set<Capability> supportedCapabilities() {
        return modelFor(ruleOutputConfig).supportedCapabilities();
    }

    // Each AnthropicChatModel opens its own HTTP client, and scoreTrace retries chat() on this same instance, so a
    // model is built only when a request first needs it and is reused after that.
    private ChatModel modelFor(Map<?, ?> outputConfig) {
        return modelsByOutputConfig.computeIfAbsent(outputConfig,
                config -> modelWithCustomParameters.apply(Map.of(OUTPUT_CONFIG, config)));
    }

    private static AnthropicFormat toAnthropicFormat(ResponseFormat responseFormat) {
        if (responseFormat == null || responseFormat.type() != ResponseFormatType.JSON
                || responseFormat.jsonSchema() == null) {
            return null;
        }
        return AnthropicFormat.fromJsonSchema(responseFormat.jsonSchema());
    }

    private static ChatRequest withoutResponseFormat(ChatRequest chatRequest) {
        return ChatRequest.builder()
                .messages(chatRequest.messages())
                .parameters(ChatRequestParameters.builder()
                        .overrideWith(chatRequest.parameters())
                        .responseFormat((ResponseFormat) null)
                        .build())
                .build();
    }
}
