package com.comet.opik.domain;

import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.Source;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

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

    @lombok.Builder
    record PersistenceContext(
            @NonNull UUID traceId,
            @NonNull String projectName,
            @NonNull ExperimentExecutionRequest.PromptVariant prompt,
            @NonNull List<ExperimentExecutionRequest.PromptVariant.Message> renderedMessages,
            ChatCompletionResponse llmResponse,
            String errorType,
            String errorMessage,
            @NonNull Instant startTime,
            @NonNull Instant endTime,
            @NonNull UUID experimentId,
            @NonNull UUID datasetId,
            String versionHash,
            @NonNull UUID datasetItemId) {
    }

    Mono<Void> persistTraceSpanAndItem(@NonNull PersistenceContext ctx) {

        ObjectNode input = buildMessagesInput(ctx.renderedMessages());
        ObjectNode output = buildLlmOutput(ctx.llmResponse());

        return Mono.when(createTrace(ctx, input, output), createSpan(ctx, input, output))
                .then(createExperimentItem(ctx.experimentId(), ctx.datasetItemId(), ctx.traceId(),
                        ctx.projectName()));
    }

    private Mono<Void> createTrace(PersistenceContext ctx, ObjectNode input, ObjectNode output) {

        ObjectNode metadata = JsonUtils.createObjectNode();
        metadata.put("created_from", "playground");
        metadata.put("test_suite_dataset_id", ctx.datasetId().toString());
        if (ctx.versionHash() != null) {
            metadata.put("test_suite_dataset_version_hash", ctx.versionHash());
        }
        metadata.put("test_suite_dataset_item_id", ctx.datasetItemId().toString());
        metadata.put("test_suite_model", ctx.prompt().model());

        var traceBuilder = Trace.builder()
                .id(ctx.traceId())
                .projectName(ctx.projectName())
                .name(TRACE_SPAN_NAME)
                .startTime(ctx.startTime())
                .endTime(ctx.endTime())
                .input(input)
                .output(output)
                .metadata(metadata)
                .source(Source.EXPERIMENT);

        if (ctx.errorMessage() != null) {
            traceBuilder.errorInfo(ErrorInfo.builder()
                    .exceptionType(ctx.errorType())
                    .message(ctx.errorMessage())
                    .traceback(ctx.errorMessage())
                    .build());
        }

        var trace = traceBuilder.build();

        return traceService.create(trace)
                .then();
    }

    private Mono<Void> createSpan(PersistenceContext ctx, ObjectNode input, ObjectNode output) {

        Map<String, Integer> usage = null;
        if (ctx.llmResponse() != null && ctx.llmResponse().usage() != null) {
            usage = new HashMap<>();
            var u = ctx.llmResponse().usage();
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

        var resolvedModelInfo = llmProviderFactory.getResolvedModelInfo(ctx.prompt().model());

        var spanBuilder = Span.builder()
                .id(idGenerator.generateId())
                .traceId(ctx.traceId())
                .projectName(ctx.projectName())
                .type(SpanType.llm)
                .name(TRACE_SPAN_NAME)
                .startTime(ctx.startTime())
                .endTime(ctx.endTime())
                .input(input)
                .output(output)
                .model(resolvedModelInfo.actualModel())
                .provider(resolvedModelInfo.provider())
                .usage(usage)
                .source(Source.EXPERIMENT);

        if (ctx.errorMessage() != null) {
            spanBuilder.errorInfo(ErrorInfo.builder()
                    .exceptionType(ctx.errorType())
                    .message(ctx.errorMessage())
                    .traceback(ctx.errorMessage())
                    .build());
        }

        var span = spanBuilder.build();

        return spanService.create(span)
                .then();
    }

    private Mono<Void> createExperimentItem(UUID experimentId, UUID datasetItemId, UUID traceId,
            String projectName) {

        var item = ExperimentItem.builder()
                .id(idGenerator.generateId())
                .experimentId(experimentId)
                .datasetItemId(datasetItemId)
                .traceId(traceId)
                .projectName(projectName)
                .build();

        return experimentItemService.create(Set.of(item))
                .then();
    }

    private ObjectNode buildMessagesInput(
            List<ExperimentExecutionRequest.PromptVariant.Message> renderedMessages) {
        ObjectNode input = JsonUtils.createObjectNode();
        var messagesArray = JsonUtils.createArrayNode();
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
