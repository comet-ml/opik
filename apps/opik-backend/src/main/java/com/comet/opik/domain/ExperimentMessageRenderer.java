package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.domain.template.MustacheParser;
import com.comet.opik.utils.JsonUtils;
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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ExperimentMessageRenderer {

    private final @NonNull MustacheParser mustacheParser;

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
                        String rendered = mustacheParser.renderUnescaped(msg.content().asText(), context);
                        return ExperimentExecutionRequest.PromptVariant.Message.builder()
                                .role(msg.role())
                                .content(JsonUtils.valueToTree(rendered))
                                .build();
                    }
                    if (msg.content().isArray()) {
                        var renderedParts = JsonUtils.getMapper().createArrayNode();
                        for (JsonNode part : msg.content()) {
                            if (part.has("text")) {
                                String rendered = mustacheParser.renderUnescaped(part.get("text").asText(), context);
                                var renderedPart = ((ObjectNode) part.deepCopy()).put("text", rendered);
                                renderedParts.add(renderedPart);
                            } else {
                                renderedParts.add(part);
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

    private void applyConfigs(ChatCompletionRequest.Builder builder, Map<String, JsonNode> configs) {
        var temperature = configs.get("temperature");
        if (temperature != null && temperature.isNumber()) {
            builder.temperature(temperature.doubleValue());
        }

        var maxCompletionTokens = configs.get("maxCompletionTokens");
        if (maxCompletionTokens != null && maxCompletionTokens.isNumber()) {
            builder.maxCompletionTokens(maxCompletionTokens.intValue());
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
    }
}
