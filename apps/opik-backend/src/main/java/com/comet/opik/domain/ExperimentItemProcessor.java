package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.Source;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.template.MustacheParser;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.openai.internal.chat.AssistantMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.chat.Message;
import dev.langchain4j.model.openai.internal.chat.SystemMessage;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ExperimentItemProcessor {

    private static final String TRACE_SPAN_NAME = "chat_completion_create";

    private final @NonNull ChatCompletionService chatCompletionService;
    private final @NonNull LlmProviderFactory llmProviderFactory;
    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;
    private final @NonNull ExperimentItemService experimentItemService;
    private final @NonNull IdGenerator idGenerator;
    private final MustacheParser mustacheParser = new MustacheParser();

    public void process(
            @NonNull ExperimentExecutionRequest.PromptVariant prompt,
            @NonNull DatasetItem datasetItem,
            @NonNull UUID experimentId,
            @NonNull UUID datasetId,
            String versionHash,
            @NonNull String projectName,
            @NonNull String workspaceId,
            @NonNull String userName) {

        UUID traceId = idGenerator.generateId();
        Instant startTime = Instant.now();
        ChatCompletionResponse llmResponse = null;
        String errorMessage = null;

        Map<String, Object> templateContext = buildTemplateContext(datasetItem);

        List<ExperimentExecutionRequest.PromptVariant.Message> renderedMessages = renderMessages(
                prompt.messages(), templateContext);

        try {
            var chatRequest = buildChatCompletionRequest(prompt, renderedMessages);
            llmResponse = chatCompletionService.create(chatRequest, workspaceId);
        } catch (Exception e) {
            log.warn("LLM call failed for experiment '{}', dataset item '{}'",
                    experimentId, datasetItem.id(), e);
            errorMessage = e.getMessage();
        }

        Instant endTime = Instant.now();

        createTrace(traceId, projectName, renderedMessages, llmResponse, errorMessage,
                startTime, endTime, datasetId, versionHash, datasetItem.id(), workspaceId, userName);

        createSpan(traceId, projectName, prompt, renderedMessages, llmResponse, errorMessage,
                startTime, endTime, workspaceId, userName);

        createExperimentItem(experimentId, datasetItem.id(), traceId, projectName, workspaceId, userName);
    }

    private Map<String, Object> buildTemplateContext(DatasetItem datasetItem) {
        if (datasetItem.data() == null) {
            return Map.of();
        }
        // Mustache context requires String values; JsonNode must be converted
        var context = new HashMap<String, Object>();
        datasetItem.data().forEach((key, value) -> context.put(key,
                value.isTextual() ? value.asText() : value.toString()));
        return context;
    }

    private List<ExperimentExecutionRequest.PromptVariant.Message> renderMessages(
            List<ExperimentExecutionRequest.PromptVariant.Message> messages,
            Map<String, Object> context) {

        return messages.stream()
                .map(msg -> {
                    if (msg.content().isTextual()) {
                        String rendered = mustacheParser.renderUnescaped(msg.content().asText(), context);
                        return ExperimentExecutionRequest.PromptVariant.Message.builder()
                                .role(msg.role())
                                .content(JsonUtils.getJsonNodeFromString("\"" + escapeJsonString(rendered) + "\""))
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

    private String escapeJsonString(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private ChatCompletionRequest buildChatCompletionRequest(
            ExperimentExecutionRequest.PromptVariant prompt,
            List<ExperimentExecutionRequest.PromptVariant.Message> renderedMessages) {

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

    /** Config keys use camelCase matching the frontend form field names */
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

    private ObjectNode buildMessagesInput(
            List<ExperimentExecutionRequest.PromptVariant.Message> renderedMessages) {
        ObjectNode input = JsonUtils.createObjectNode();
        var messagesArray = JsonUtils.getMapper().createArrayNode();
        for (var msg : renderedMessages) {
            var msgNode = JsonUtils.createObjectNode();
            msgNode.put("role", msg.role());
            msgNode.set("content", msg.content());
            messagesArray.add(msgNode);
        }
        input.set("messages", messagesArray);
        return input;
    }

    private ObjectNode buildLlmOutput(ChatCompletionResponse llmResponse) {
        ObjectNode output = JsonUtils.createObjectNode();
        if (llmResponse != null && llmResponse.choices() != null && !llmResponse.choices().isEmpty()) {
            var choice = llmResponse.choices().getFirst();
            if (choice.message() != null && choice.message().content() != null) {
                output.put("output", choice.message().content());
            }
        }
        return output;
    }

    private void createTrace(UUID traceId, String projectName,
            List<ExperimentExecutionRequest.PromptVariant.Message> renderedMessages,
            ChatCompletionResponse llmResponse, String errorMessage,
            Instant startTime, Instant endTime,
            UUID datasetId, String versionHash, UUID datasetItemId,
            String workspaceId, String userName) {

        ObjectNode input = buildMessagesInput(renderedMessages);
        ObjectNode output = buildLlmOutput(llmResponse);

        ObjectNode metadata = JsonUtils.createObjectNode();
        metadata.put("created_from", "playground");
        metadata.put("eval_suite_dataset_id", datasetId.toString());
        if (versionHash != null) {
            metadata.put("eval_suite_version_hash", versionHash);
        }
        metadata.put("eval_suite_dataset_item_id", datasetItemId.toString());

        var trace = Trace.builder()
                .id(traceId)
                .projectName(projectName)
                .name(TRACE_SPAN_NAME)
                .startTime(startTime)
                .endTime(endTime)
                .input(input)
                .output(output)
                .metadata(metadata)
                .source(Source.EXPERIMENT)
                .build();

        traceService.create(trace)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.VISIBILITY, com.comet.opik.api.Visibility.PRIVATE))
                .block();
    }

    private void createSpan(UUID traceId, String projectName,
            ExperimentExecutionRequest.PromptVariant prompt,
            List<ExperimentExecutionRequest.PromptVariant.Message> renderedMessages,
            ChatCompletionResponse llmResponse, String errorMessage,
            Instant startTime, Instant endTime,
            String workspaceId, String userName) {

        ObjectNode input = buildMessagesInput(renderedMessages);
        ObjectNode output = buildLlmOutput(llmResponse);

        Map<String, Integer> usage = null;
        if (llmResponse != null && llmResponse.usage() != null) {
            usage = new HashMap<>();
            var u = llmResponse.usage();
            if (u.completionTokens() != null) {
                usage.put("completion_tokens", u.completionTokens());
            }
            if (u.promptTokens() != null) {
                usage.put("prompt_tokens", u.promptTokens());
            }
            if (u.totalTokens() != null) {
                usage.put("total_tokens", u.totalTokens());
            }
        }

        var resolvedModelInfo = llmProviderFactory.getResolvedModelInfo(prompt.model());

        var span = Span.builder()
                .id(idGenerator.generateId())
                .traceId(traceId)
                .projectName(projectName)
                .type(SpanType.llm)
                .name(TRACE_SPAN_NAME)
                .startTime(startTime)
                .endTime(endTime)
                .input(input)
                .output(output)
                .model(resolvedModelInfo.actualModel())
                .provider(resolvedModelInfo.provider())
                .usage(usage)
                .build();

        spanService.create(span)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.VISIBILITY, com.comet.opik.api.Visibility.PRIVATE))
                .block();
    }

    private void createExperimentItem(UUID experimentId, UUID datasetItemId, UUID traceId,
            String projectName, String workspaceId, String userName) {

        var item = ExperimentItem.builder()
                .id(idGenerator.generateId())
                .experimentId(experimentId)
                .datasetItemId(datasetItemId)
                .traceId(traceId)
                .build();

        experimentItemService.create(Set.of(item))
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.VISIBILITY, com.comet.opik.api.Visibility.PRIVATE))
                .block();
    }
}
