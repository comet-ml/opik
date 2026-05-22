package com.comet.opik.domain;

import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.Source;
import com.comet.opik.api.Span;
import com.comet.opik.api.TestSuiteMetadataKeys;
import com.comet.opik.api.Trace;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ExperimentTracePersistence {

    private static final String TRACE_SPAN_NAME = "chat_completion_create";
    private static final String OPIK_PROMPTS_METADATA_KEY = "opik_prompts";

    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;
    private final @NonNull ExperimentItemService experimentItemService;
    private final @NonNull LlmProviderFactory llmProviderFactory;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull PromptService promptService;

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
            @NonNull UUID datasetItemId,
            List<Experiment.PromptVersionLink> promptVersions) {
    }

    Mono<Void> persistTraceSpanAndItem(@NonNull PersistenceContext ctx) {

        ObjectNode input = buildMessagesInput(ctx.renderedMessages());
        ObjectNode output = buildLlmOutput(ctx.llmResponse(), ctx.errorType(), ctx.errorMessage());

        return Mono.when(createTrace(ctx, input, output), createSpan(ctx, input, output))
                .then(createExperimentItem(ctx.experimentId(), ctx.datasetItemId(), ctx.traceId(),
                        ctx.projectName()));
    }

    private Mono<Void> createTrace(PersistenceContext ctx, ObjectNode input, ObjectNode output) {

        return resolveOpikPrompts(ctx.promptVersions())
                .map(opikPrompts -> buildTrace(ctx, input, output, opikPrompts))
                .flatMap(trace -> traceService.create(trace).then());
    }

    private Trace buildTrace(PersistenceContext ctx, ObjectNode input, ObjectNode output, ArrayNode opikPrompts) {

        ObjectNode metadata = JsonUtils.createObjectNode();
        metadata.put("created_from", "playground");
        metadata.put(TestSuiteMetadataKeys.DATASET_ID, ctx.datasetId().toString());
        if (ctx.versionHash() != null) {
            metadata.put(TestSuiteMetadataKeys.DATASET_VERSION_HASH, ctx.versionHash());
        }
        metadata.put(TestSuiteMetadataKeys.DATASET_ITEM_ID, ctx.datasetItemId().toString());
        metadata.put(TestSuiteMetadataKeys.MODEL, ctx.prompt().model());
        metadata.put(TestSuiteMetadataKeys.EXPERIMENT_ID, ctx.experimentId().toString());
        if (opikPrompts != null && !opikPrompts.isEmpty()) {
            metadata.set(OPIK_PROMPTS_METADATA_KEY, opikPrompts);
        }

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

        return traceBuilder.build();
    }

    private Mono<ArrayNode> resolveOpikPrompts(List<Experiment.PromptVersionLink> promptVersions) {
        if (promptVersions == null || promptVersions.isEmpty()) {
            return Mono.just(JsonUtils.createArrayNode());
        }

        Set<UUID> versionIds = promptVersions.stream()
                .map(Experiment.PromptVersionLink::id)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        if (versionIds.isEmpty()) {
            return Mono.just(JsonUtils.createArrayNode());
        }

        return promptService.findVersionByIds(versionIds)
                .map(versionsById -> buildOpikPromptsArray(promptVersions, versionsById))
                .onErrorResume(error -> {
                    log.warn("Failed to resolve prompt versions for opik_prompts metadata; "
                            + "trace will not be linked to its prompt(s)",
                            error);
                    return Mono.just(JsonUtils.createArrayNode());
                });
    }

    private ArrayNode buildOpikPromptsArray(List<Experiment.PromptVersionLink> promptVersions,
            Map<UUID, PromptVersion> versionsById) {
        ArrayNode array = JsonUtils.createArrayNode();
        for (Experiment.PromptVersionLink link : promptVersions) {
            PromptVersion version = versionsById.get(link.id());
            if (version == null) {
                continue;
            }
            ObjectNode promptNode = JsonUtils.createObjectNode();
            promptNode.put("id", version.promptId() != null
                    ? version.promptId().toString()
                    : (link.promptId() != null ? link.promptId().toString() : null));
            if (link.promptName() != null) {
                promptNode.put("name", link.promptName());
            }
            if (version.templateStructure() != null) {
                promptNode.put("template_structure", version.templateStructure().getValue());
            }

            ObjectNode versionNode = JsonUtils.createObjectNode();
            versionNode.put("id", version.id().toString());
            if (version.template() != null) {
                versionNode.set("template", JsonUtils.getJsonNodeFromStringWithFallback(version.template()));
            }
            if (version.commit() != null) {
                versionNode.put("commit", version.commit());
            }
            if (version.versionNumber() != null) {
                versionNode.put("version_number", version.versionNumber());
            }
            if (version.metadata() != null) {
                versionNode.set("metadata", version.metadata());
            }
            promptNode.set("version", versionNode);

            array.add(promptNode);
        }
        return array;
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

    private ObjectNode buildLlmOutput(ChatCompletionResponse llmResponse, String errorType, String errorMessage) {
        ObjectNode output = JsonUtils.createObjectNode();
        if (llmResponse != null && llmResponse.choices() != null && !llmResponse.choices().isEmpty()) {
            var choice = llmResponse.choices().getFirst();
            if (choice.message() != null && choice.message().content() != null) {
                output.put("output", choice.message().content());
            }
        }
        // When the agent LLM call failed, surface the error inside the persisted output so the
        // assertion judge (which reads trace.output) can recognise the fault state instead of
        // fabricating verdicts against an empty `{}`. The structured ErrorInfo on the trace itself
        // is set separately in createTrace; the assertion path doesn't consume that field today.
        if (errorMessage != null) {
            ObjectNode errorNode = JsonUtils.createObjectNode();
            if (errorType != null) {
                errorNode.put("type", errorType);
            }
            errorNode.put("message", errorMessage);
            output.set("error", errorNode);
        }
        return output;
    }
}
