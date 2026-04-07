package com.comet.opik.domain;

import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.Source;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Visibility;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ExperimentTracePersistence {

    private static final String TRACE_SPAN_NAME = "chat_completion_create";

    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;
    private final @NonNull ExperimentItemService experimentItemService;
    private final @NonNull LlmProviderFactory llmProviderFactory;
    private final @NonNull IdGenerator idGenerator;

    void persistTraceSpanAndItem(
            @NonNull UUID traceId,
            @NonNull String projectName,
            @NonNull ExperimentExecutionRequest.PromptVariant prompt,
            @NonNull List<ExperimentExecutionRequest.PromptVariant.Message> renderedMessages,
            ChatCompletionResponse llmResponse,
            String errorMessage,
            @NonNull Instant startTime,
            @NonNull Instant endTime,
            @NonNull UUID experimentId,
            @NonNull UUID datasetId,
            String versionHash,
            @NonNull UUID datasetItemId,
            @NonNull String workspaceId,
            @NonNull String userName) {

        ObjectNode input = buildMessagesInput(renderedMessages);
        ObjectNode output = buildLlmOutput(llmResponse);

        createTrace(traceId, projectName, input, output, errorMessage,
                startTime, endTime, datasetId, versionHash, datasetItemId, workspaceId, userName);

        createSpan(traceId, projectName, prompt, input, output, llmResponse, errorMessage,
                startTime, endTime, workspaceId, userName);

        createExperimentItem(experimentId, datasetItemId, traceId, workspaceId, userName);
    }

    private void createTrace(UUID traceId, String projectName,
            ObjectNode input, ObjectNode output, String errorMessage,
            Instant startTime, Instant endTime,
            UUID datasetId, String versionHash, UUID datasetItemId,
            String workspaceId, String userName) {

        ObjectNode metadata = JsonUtils.createObjectNode();
        metadata.put("created_from", "playground");
        metadata.put("eval_suite_dataset_id", datasetId.toString());
        if (versionHash != null) {
            metadata.put("eval_suite_dataset_version_hash", versionHash);
        }
        metadata.put("eval_suite_dataset_item_id", datasetItemId.toString());

        var traceBuilder = Trace.builder()
                .id(traceId)
                .projectName(projectName)
                .name(TRACE_SPAN_NAME)
                .startTime(startTime)
                .endTime(endTime)
                .input(input)
                .output(output)
                .metadata(metadata)
                .source(Source.EXPERIMENT);

        if (errorMessage != null) {
            traceBuilder.errorInfo(ErrorInfo.builder()
                    .exceptionType("LlmProviderError")
                    .message(errorMessage)
                    .traceback(errorMessage)
                    .build());
        }

        var trace = traceBuilder.build();

        traceService.create(trace)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.VISIBILITY, Visibility.PRIVATE))
                .block();
    }

    private void createSpan(UUID traceId, String projectName,
            ExperimentExecutionRequest.PromptVariant prompt,
            ObjectNode input, ObjectNode output,
            ChatCompletionResponse llmResponse, String errorMessage,
            Instant startTime, Instant endTime,
            String workspaceId, String userName) {

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

        var spanBuilder = Span.builder()
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
                .source(Source.EXPERIMENT);

        if (errorMessage != null) {
            spanBuilder.errorInfo(ErrorInfo.builder()
                    .exceptionType("LlmProviderError")
                    .message(errorMessage)
                    .traceback(errorMessage)
                    .build());
        }

        var span = spanBuilder.build();

        spanService.create(span)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.VISIBILITY, Visibility.PRIVATE))
                .block();
    }

    private void createExperimentItem(UUID experimentId, UUID datasetItemId, UUID traceId,
            String workspaceId, String userName) {

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
                        .put(RequestContext.VISIBILITY, Visibility.PRIVATE))
                .block();
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
}
