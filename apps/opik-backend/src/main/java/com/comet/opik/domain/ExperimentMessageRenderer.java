package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.template.MustacheParser;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.openai.internal.chat.AssistantMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.SystemMessage;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ExperimentMessageRenderer {

    private static final List<String> MEDIA_URL_FIELDS = List.of("image_url", "video_url", "audio_url");

    private static final Pattern SINGLE_VARIABLE = Pattern.compile(
            "^\\s*(?:\\{\\{\\{\\s*([^{}]+?)\\s*}}}|\\{\\{\\s*&?\\s*([^{}#^/!>=&][^{}]*?)\\s*}})\\s*$");

    private final @NonNull MustacheParser mustacheParser;
    private final @NonNull LlmProviderFactory llmProviderFactory;

    Map<String, Object> buildTemplateContext(@NonNull DatasetItem datasetItem) {
        if (datasetItem.data() == null) {
            return Map.of();
        }
        var context = new HashMap<String, Object>();
        datasetItem.data().forEach((key, value) -> context.put(key,
                value.isTextual() ? value.asText() : value.toString()));
        return context;
    }

    List<ExperimentExecutionRequest.PromptVariant.Message> renderMessages(
            @NonNull List<ExperimentExecutionRequest.PromptVariant.Message> messages,
            @NonNull Map<String, Object> context) {

        return messages.stream()
                .map(msg -> {
                    if (msg.content().isTextual()) {
                        String rendered = mustacheParser.render(msg.content().asText(), context);
                        return ExperimentExecutionRequest.PromptVariant.Message.builder()
                                .role(msg.role())
                                .content(JsonUtils.valueToTree(rendered))
                                .build();
                    }
                    if (msg.content().isArray()) {
                        var renderedParts = JsonUtils.getMapper().createArrayNode();
                        for (JsonNode part : msg.content()) {
                            if (part.has("text")) {
                                String rendered = mustacheParser.render(part.get("text").asText(), context);
                                var renderedPart = ((ObjectNode) part.deepCopy()).put("text", rendered);
                                renderedParts.add(renderedPart);
                            } else {
                                renderedParts.addAll(renderMediaPart(part, context));
                            }
                        }
                        return ExperimentExecutionRequest.PromptVariant.Message.builder()
                                .role(msg.role())
                                .content(renderedParts)
                                .build();
                    }
                    return msg;
                })
                .toList();
    }

    private List<JsonNode> renderMediaPart(JsonNode part, Map<String, Object> context) {
        var field = MEDIA_URL_FIELDS.stream()
                .filter(name -> part.path(name).path("url").isTextual())
                .findFirst();
        if (field.isEmpty()) {
            return List.of(part);
        }

        String template = part.get(field.get()).get("url").asText();
        List<String> urls = getListFromSingleVariable(template, context)
                .orElseGet(() -> List.of(mustacheParser.render(template, context)));

        return urls.stream()
                .<JsonNode>map(url -> {
                    ObjectNode renderedPart = part.deepCopy();
                    ((ObjectNode) renderedPart.get(field.get())).put("url", url);
                    return renderedPart;
                })
                .toList();
    }

    /**
     * The context holds a dataset list as JSON text: {@link #buildTemplateContext} serialises arrays, and CSV
     * uploads store every cell as text.
     */
    private Optional<List<String>> getListFromSingleVariable(String template, Map<String, Object> context) {
        var matcher = SINGLE_VARIABLE.matcher(template);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String name = Optional.ofNullable(matcher.group(1)).orElse(matcher.group(2));
        if (!(context.get(name) instanceof String value) || !value.strip().startsWith("[")) {
            return Optional.empty();
        }

        JsonNode parsed;
        try {
            parsed = JsonUtils.getJsonNodeFromString(value);
        } catch (UncheckedIOException e) {
            return Optional.empty();
        }
        if (!parsed.isArray()) {
            return Optional.empty();
        }

        var items = new ArrayList<String>();
        parsed.forEach(item -> items.add(item.isTextual() ? item.asText() : item.toString()));
        return Optional.of(items);
    }

    ChatCompletionRequest buildChatCompletionRequest(
            @NonNull ExperimentExecutionRequest.PromptVariant prompt,
            @NonNull List<ExperimentExecutionRequest.PromptVariant.Message> renderedMessages) {

        List<Message> messages = renderedMessages.stream()
                .map(this::toLangChain4jMessage)
                .toList();

        var builder = ChatCompletionRequest.builder()
                .model(prompt.model())
                .messages(messages)
                .stream(false);

        if (prompt.configs() != null) {
            applyConfigs(builder, prompt.configs());
            applyReasoningEffort(builder, prompt.model(), prompt.configs().get("reasoningEffort"));
        }

        return builder.build();
    }

    private Message toLangChain4jMessage(ExperimentExecutionRequest.PromptVariant.Message msg) {
        String contentStr = msg.content().isTextual()
                ? msg.content().asText()
                : msg.content().toString();

        return switch (msg.role().toLowerCase()) {
            case "system" -> SystemMessage.builder().content(contentStr).build();
            case "assistant" -> AssistantMessage.builder().content(contentStr).build();
            default -> {
                var builder = com.comet.opik.domain.llm.langchain4j.OpikUserMessage.builder();
                if (msg.content().isTextual()) {
                    builder.content(contentStr);
                } else if (msg.content().isArray()) {
                    for (JsonNode part : msg.content()) {
                        String type = part.path("type").asText("");
                        switch (type) {
                            case "text", "input_text" -> builder.addText(part.path("text").asText(""));
                            case "image_url" -> {
                                String url = part.path("image_url").path("url").asText(null);
                                if (url != null) {
                                    builder.addImageUrl(url);
                                }
                            }
                            case "video_url" -> {
                                String url = part.path("video_url").path("url").asText(null);
                                if (url != null) {
                                    builder.addVideoUrl(url);
                                }
                            }
                            case "audio_url" -> {
                                String url = part.path("audio_url").path("url").asText(null);
                                if (url != null) {
                                    builder.addAudioUrl(url);
                                }
                            }
                            default -> builder.addText(part.toString());
                        }
                    }
                } else {
                    builder.content(contentStr);
                }
                yield builder.build();
            }
        };
    }

    /**
     * Only the playground's OpenAI panel stores an effort under this key. Every other provider path
     * either ignores the field (Anthropic, Gemini, Vertex), replaces it (the free model), or forwards
     * it as-is to an endpoint that may reject it (OpenRouter, custom, Ollama, Bedrock), so it is set
     * for OpenAI models only.
     */
    private void applyReasoningEffort(ChatCompletionRequest.Builder builder, String model, JsonNode effort) {
        if (effort == null || !effort.isTextual() || StringUtils.isBlank(effort.asText())) {
            return;
        }
        if (llmProviderFactory.getLlmProvider(model) == LlmProvider.OPEN_AI) {
            builder.reasoningEffort(effort.asText());
        }
    }

    private void applyConfigs(ChatCompletionRequest.Builder builder, Map<String, JsonNode> configs) {
        var temperature = configs.get("temperature");
        if (temperature != null && temperature.isNumber()) {
            builder.temperature(temperature.doubleValue());
        }

        var maxCompletionTokens = configs.get("maxCompletionTokens");
        if (maxCompletionTokens != null && maxCompletionTokens.isNumber()) {
            builder.maxCompletionTokens(maxCompletionTokens.intValue());
        }

        // Only OpenRouter's config carries this key, and its panel uses 0 for "no limit", not a zero cap.
        var maxTokens = configs.get("maxTokens");
        if (maxTokens != null && maxTokens.isNumber() && maxTokens.intValue() > 0) {
            builder.maxTokens(maxTokens.intValue());
        }

        var topP = configs.get("topP");
        if (topP != null && topP.isNumber()) {
            builder.topP(topP.doubleValue());
        }

        var frequencyPenalty = configs.get("frequencyPenalty");
        if (frequencyPenalty != null && frequencyPenalty.isNumber()) {
            builder.frequencyPenalty(frequencyPenalty.doubleValue());
        }

        var presencePenalty = configs.get("presencePenalty");
        if (presencePenalty != null && presencePenalty.isNumber()) {
            builder.presencePenalty(presencePenalty.doubleValue());
        }

        // Provider-specific parameters the flat config above cannot express — today that means Gemini
        // and Vertex thinking, and Anthropic's output_config. Only an object converts to a Map;
        // Jackson throws on an array or scalar.
        //
        // Not Anthropic extended thinking: LlmProviderAnthropicMapper forwards only output_config from
        // custom_parameters, so a thinking block never reaches AnthropicCreateMessageRequest, while
        // its thinkingEnabled(request) does read custom_parameters — so forwarding a thinking block
        // on that path gates temperature/top_p off without turning thinking on. Wiring that up is its
        // own change; until then an Anthropic experiment should not carry the block.
        var customParameters = configs.get("custom_parameters");
        if (customParameters != null && customParameters.isObject()) {
            builder.customParameters(JsonUtils.getMapper()
                    .convertValue(customParameters, new TypeReference<Map<String, Object>>() {
                    }));
        }
    }
}
