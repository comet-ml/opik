package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.domain.llm.MessageContentNormalizer;
import com.comet.opik.domain.llm.langchain4j.OpikContent;
import com.comet.opik.domain.llm.langchain4j.OpikUserMessage;
import com.comet.opik.infrastructure.llm.OpenAiCompatFinishReasons;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.internal.chat.AssistantMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionChoice;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.chat.Delta;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.SystemMessage;
import dev.langchain4j.model.openai.internal.chat.UserMessage;
import dev.langchain4j.model.openai.internal.shared.Usage;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import static com.comet.opik.domain.llm.ChatCompletionService.UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;

@UtilityClass
class BedrockConverseMapper {

    private static final String ASSISTANT = "assistant";
    private static final String USER = "user";
    private static final Pattern IMAGE_DATA_URL = Pattern.compile("^data:image/([a-zA-Z0-9.+-]+);base64,(.+)$",
            Pattern.DOTALL);
    private static final Set<String> IMAGE_FORMATS = Set.of("png", "jpeg", "gif", "webp");

    private record Turn(String role, ArrayNode content) {
    }

    ObjectNode toConverseRequest(ChatCompletionRequest request) {
        var system = JsonUtils.createArrayNode();
        var turns = new ArrayList<Turn>();
        for (Message message : request.messages()) {
            switch (message) {
                case SystemMessage systemMessage -> addText(system, systemMessage.content());
                case AssistantMessage assistantMessage -> addTurn(turns, ASSISTANT, text(assistantMessage.content()));
                case OpikUserMessage opikUserMessage -> addTurn(turns, USER, userContent(opikUserMessage.content()));
                case UserMessage userMessage ->
                    addTurn(turns, USER, text(MessageContentNormalizer.flattenContent(userMessage.content())));
                default -> {
                }
            }
        }
        var body = body(system, turns);
        var inference = JsonUtils.createObjectNode();
        Optional.ofNullable(ObjectUtils.firstNonNull(positiveOrNull(request.maxCompletionTokens()),
                positiveOrNull(request.maxTokens()))).ifPresent(maxTokens -> inference.put("maxTokens", maxTokens));
        Optional.ofNullable(request.temperature()).ifPresent(temperature -> inference.put("temperature", temperature));
        Optional.ofNullable(request.topP()).ifPresent(topP -> inference.put("topP", topP));
        if (CollectionUtils.isNotEmpty(request.stop())) {
            request.stop().forEach(inference.putArray("stopSequences")::add);
        }
        if (!inference.isEmpty()) {
            body.set("inferenceConfig", inference);
        }
        if (MapUtils.isNotEmpty(request.customParameters())) {
            body.set("additionalModelRequestFields", JsonUtils.valueToTree(request.customParameters()));
        }
        return body;
    }

    ObjectNode toConverseRequest(List<ChatMessage> messages, ChatRequestParameters parameters) {
        var system = JsonUtils.createArrayNode();
        var turns = new ArrayList<Turn>();
        for (ChatMessage message : messages) {
            switch (message) {
                case dev.langchain4j.data.message.SystemMessage systemMessage ->
                    addText(system, systemMessage.text());
                case dev.langchain4j.data.message.UserMessage userMessage ->
                    addTurn(turns, USER, contents(userMessage.contents()));
                case AiMessage aiMessage -> addTurn(turns, ASSISTANT, text(aiMessage.text()));
                default -> {
                }
            }
        }
        var body = body(system, turns);
        var inference = JsonUtils.createObjectNode();
        Optional.ofNullable(parameters.maxOutputTokens()).ifPresent(maxTokens -> inference.put("maxTokens", maxTokens));
        Optional.ofNullable(parameters.temperature())
                .ifPresent(temperature -> inference.put("temperature", temperature));
        Optional.ofNullable(parameters.topP()).ifPresent(topP -> inference.put("topP", topP));
        if (CollectionUtils.isNotEmpty(parameters.stopSequences())) {
            parameters.stopSequences().forEach(inference.putArray("stopSequences")::add);
        }
        if (!inference.isEmpty()) {
            body.set("inferenceConfig", inference);
        }
        return body;
    }

    ChatCompletionResponse toResponse(JsonNode response, String model) {
        return ChatCompletionResponse.builder()
                .model(model)
                .choices(List.of(ChatCompletionChoice.builder()
                        .index(0)
                        .message(AssistantMessage.builder().content(text(response)).build())
                        .finishReason(OpenAiCompatFinishReasons.toWireValue(finishReason(stopReason(response))))
                        .build()))
                .usage(usage(response.path("usage")))
                .build();
    }

    ChatResponse toChatResponse(JsonNode response, String model) {
        var usage = response.path("usage");
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(text(response)))
                .modelName(model)
                .finishReason(finishReason(stopReason(response)))
                .tokenUsage(usage.isObject()
                        ? new TokenUsage(intOrNull(usage, "inputTokens"), intOrNull(usage, "outputTokens"),
                                intOrNull(usage, "totalTokens"))
                        : null)
                .build();
    }

    ChatCompletionResponse deltaChunk(String model, String content, String finishReason, Usage usage) {
        return ChatCompletionResponse.builder()
                .model(model)
                .choices(List.of(ChatCompletionChoice.builder()
                        .delta(Delta.builder().content(content).role(ASSISTANT).build())
                        .finishReason(finishReason)
                        .build()))
                .usage(usage)
                .build();
    }

    FinishReason finishReason(String stopReason) {
        if (stopReason == null) {
            return null;
        }
        return switch (stopReason) {
            case "end_turn", "stop_sequence" -> FinishReason.STOP;
            case "max_tokens", "model_context_window_exceeded" -> FinishReason.LENGTH;
            case "tool_use" -> FinishReason.TOOL_EXECUTION;
            case "content_filtered", "guardrail_intervened" -> FinishReason.CONTENT_FILTER;
            default -> FinishReason.OTHER;
        };
    }

    Usage usage(JsonNode usage) {
        if (!usage.isObject()) {
            return null;
        }
        return Usage.builder()
                .promptTokens(intOrNull(usage, "inputTokens"))
                .completionTokens(intOrNull(usage, "outputTokens"))
                .totalTokens(intOrNull(usage, "totalTokens"))
                .build();
    }

    String errorMessage(String body) {
        if (StringUtils.isBlank(body)) {
            return UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;
        }
        var json = JsonUtils.getJsonNodeFromStringWithFallback(body);
        var message = json.has("message") ? json.get("message") : json.path("Message");
        return message.isTextual() && StringUtils.isNotBlank(message.asText())
                ? message.asText()
                : UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;
    }

    private static ObjectNode body(ArrayNode system, List<Turn> turns) {
        var body = JsonUtils.createObjectNode();
        var messages = body.putArray("messages");
        turns.forEach(turn -> messages.addObject().put("role", turn.role()).set("content", turn.content()));
        if (!system.isEmpty()) {
            body.set("system", system);
        }
        return body;
    }

    // Converse rejects two turns in a row from one role, which the playground can send.
    private static void addTurn(List<Turn> turns, String role, ArrayNode content) {
        if (content.isEmpty()) {
            return;
        }
        if (!turns.isEmpty() && turns.getLast().role().equals(role)) {
            turns.getLast().content().addAll(content);
            return;
        }
        turns.add(new Turn(role, content));
    }

    // Converse rejects a blank text block.
    private static void addText(ArrayNode blocks, String text) {
        if (StringUtils.isNotBlank(text)) {
            blocks.addObject().put("text", text);
        }
    }

    private static ArrayNode text(String text) {
        var blocks = JsonUtils.createArrayNode();
        addText(blocks, text);
        return blocks;
    }

    private static ArrayNode userContent(Object rawContent) {
        if (!(rawContent instanceof List<?> contentList)) {
            return text(MessageContentNormalizer.flattenContent(rawContent));
        }
        var blocks = JsonUtils.createArrayNode();
        contentList.stream()
                .filter(OpikContent.class::isInstance)
                .map(OpikContent.class::cast)
                .forEach(content -> {
                    switch (content.type()) {
                        case TEXT -> addText(blocks, content.text());
                        case IMAGE_URL -> addImage(blocks, content.imageUrl().getUrl());
                        case VIDEO_URL -> addText(blocks, "[Video: " + content.videoUrl().url() + "]");
                        default -> {
                        }
                    }
                });
        return blocks;
    }

    private static ArrayNode contents(List<Content> contents) {
        var blocks = JsonUtils.createArrayNode();
        for (Content content : contents) {
            switch (content) {
                case TextContent textContent -> addText(blocks, textContent.text());
                case ImageContent imageContent when imageContent.image().base64Data() != null ->
                    addImage(blocks, "data:%s;base64,%s".formatted(imageContent.image().mimeType(),
                            imageContent.image().base64Data()));
                case ImageContent imageContent when imageContent.image().url() != null ->
                    addImage(blocks, imageContent.image().url().toString());
                default -> {
                }
            }
        }
        return blocks;
    }

    // Converse takes image bytes, or an S3 location, but no web URL.
    private static void addImage(ArrayNode blocks, String url) {
        var matcher = IMAGE_DATA_URL.matcher(StringUtils.defaultString(url));
        var format = matcher.matches() ? matcher.group(1).toLowerCase(Locale.ROOT).replace("jpg", "jpeg") : null;
        if (format == null || !IMAGE_FORMATS.contains(format)) {
            addText(blocks, "[Image: " + url + "]");
            return;
        }
        var image = blocks.addObject().putObject("image");
        image.put("format", format);
        image.putObject("source").put("bytes", matcher.group(2));
    }

    private static String text(JsonNode response) {
        var text = new StringBuilder();
        response.path("output").path("message").path("content")
                .forEach(block -> text.append(block.path("text").asText("")));
        return text.toString();
    }

    private static String stopReason(JsonNode response) {
        var stopReason = response.path("stopReason");
        return stopReason.isTextual() ? stopReason.asText() : null;
    }

    private static Integer intOrNull(JsonNode node, String field) {
        var value = node.path(field);
        return value.canConvertToInt() ? value.asInt() : null;
    }

    private static Integer positiveOrNull(Integer tokens) {
        return tokens != null && tokens > 0 ? tokens : null;
    }
}
