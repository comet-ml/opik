package com.comet.opik.domain;

import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.Source;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.AnthropicTokenUsage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.googleai.GoogleAiGeminiTokenUsage;
import dev.langchain4j.model.openai.OpenAiTokenUsage;
import dev.langchain4j.model.output.TokenUsage;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

/**
 * Persists the LLM-as-judge online-evaluation loop as monitoring traces and spans so the cost and
 * behavior of each evaluation are auditable inside Opik (OPIK-6994).
 * <p>
 * One {@link Trace} is created per evaluation execution and one {@code llm} {@link Span} per LLM
 * round-trip (the initial call plus every agentic-tools round and the wrap-up). Spans carry token
 * usage and the resolved model/provider, so the existing span→trace cost aggregation computes the
 * evaluation's cost with no extra work.
 * <p>
 * Traces/spans carry {@link Source#EVALUATOR} and the trace is {@link VisibilityMode#HIDDEN}, so they
 * are kept out of the default traces view and aggregations like other non-SDK/hidden traces, and the
 * online-scoring samplers skip them automatically: {@code Source.isLoggingSource(EVALUATOR)} is false,
 * so the trace sampler only scores them if they carry {@code selected_rule_ids} (they never do) and
 * the span sampler filters them out. The engine therefore never evaluates its own monitoring traces.
 * <p>
 * Persistence is best-effort: a failure to write a span or trace is logged and swallowed so it can
 * never break the scoring that produced it. The one exception is a failing LLM call, whose error is
 * recorded on the span and then re-propagated so the evaluation fails as it would have before.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OnlineScoringTracePersistence {

    private static final String TRACE_NAME = "online_evaluation";
    private static final String SPAN_NAME = "llm_call";
    private static final String PREPARE_SPAN_NAME = "prepare_evaluation";

    /**
     * Size cap (chars of serialized JSON) under which the evaluated entity's input/output is kept
     * structurally intact on the prepare_evaluation span so the UI renders it in pretty mode; larger
     * payloads fall back to a truncated text node.
     */
    private static final int EVALUATED_PREVIEW_CHARS = 8_000;
    private static final String MODE_INLINE = "inline";
    private static final String MODE_AGENTIC_TOOLS = "agentic_tools";

    private final @NonNull TraceService traceService;
    private final @NonNull SpanService spanService;
    private final @NonNull LlmProviderFactory llmProviderFactory;
    private final @NonNull IdGenerator idGenerator;

    /**
     * Sink for the telemetry of one evaluation. The scorer holds a recorder and calls it
     * unconditionally, so the scoring path carries no {@code if (tracing) ...} branching. When
     * monitoring is disabled the scorer uses {@link #NOOP}, which writes nothing and passes the LLM
     * call through unchanged.
     */
    public interface EvaluationRecorder {

        /** Writes nothing; passes the LLM call through. Used when monitoring is disabled. */
        EvaluationRecorder NOOP = new EvaluationRecorder() {
            @Override
            public Mono<ChatResponse> recordLlmCall(ChatRequest request, Mono<ChatResponse> call) {
                return call;
            }

            @Override
            public Mono<String> recordToolCall(String toolName, String arguments, Mono<String> execution) {
                return execution;
            }

            @Override
            public Mono<Void> recordPreparation(int fetchedSpanCount, int estimatedTokens, boolean agentic) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> complete(List<? extends FeedbackScoreItem> scores) {
                return Mono.empty();
            }

            @Override
            public Mono<Void> fail(Throwable error) {
                return Mono.empty();
            }
        };

        /** Records one LLM round as a span; returns the response unchanged (or re-propagates the error). */
        Mono<ChatResponse> recordLlmCall(ChatRequest request, Mono<ChatResponse> call);

        /** Records one agentic tool execution as a {@code tool} span; returns the result unchanged. */
        Mono<String> recordToolCall(String toolName, String arguments, Mono<String> execution);

        /**
         * Records the upfront retrieval + context-assembly phase (span fetch, size estimate, mode
         * decision) as a {@code general} span preceding the first LLM round.
         */
        Mono<Void> recordPreparation(int fetchedSpanCount, int estimatedTokens, boolean agentic);

        /** Finalizes the parent trace on success with the produced scores. */
        Mono<Void> complete(List<? extends FeedbackScoreItem> scores);

        /** Finalizes the parent trace on failure with the error. */
        Mono<Void> fail(Throwable error);
    }

    /**
     * The entity an online evaluation is assessing — a trace, a span, or a thread. Lets the recorder
     * be created the same way from all three scorers; the {@link Kind} drives the evaluated-id key on
     * the monitoring trace (e.g. {@code evaluated_span_id}).
     */
    @Builder(toBuilder = true)
    public record EvaluatedSubject(Kind kind, String id, UUID projectId, String projectName, String name,
            JsonNode input, JsonNode output) {

        public enum Kind {
            TRACE("evaluated_trace_id"),
            SPAN("evaluated_span_id"),
            THREAD("evaluated_thread_id");

            private final String idKey;

            Kind(String idKey) {
                this.idKey = idKey;
            }

            private String idKey() {
                return idKey;
            }
        }

        public static EvaluatedSubject ofTrace(@NonNull Trace trace) {
            return EvaluatedSubject.builder()
                    .kind(Kind.TRACE)
                    .id(trace.id().toString())
                    .projectId(trace.projectId())
                    .projectName(trace.projectName())
                    .name(trace.name())
                    .input(trace.input())
                    .output(trace.output())
                    .build();
        }

        public static EvaluatedSubject ofSpan(@NonNull Span span) {
            return EvaluatedSubject.builder()
                    .kind(Kind.SPAN)
                    .id(span.id().toString())
                    .projectId(span.projectId())
                    .projectName(span.projectName())
                    .name(span.name())
                    .input(span.input())
                    .output(span.output())
                    .build();
        }

        public static EvaluatedSubject ofThread(@NonNull String threadId, UUID projectId, String projectName) {
            // Threads have no single input/output; the prepare span carries the thread id only.
            return EvaluatedSubject.builder()
                    .kind(Kind.THREAD)
                    .id(threadId)
                    .projectId(projectId)
                    .projectName(projectName)
                    .build();
        }
    }

    /**
     * Per-evaluation handle. Holds the parent trace id, the evaluated entity references and the
     * resolved model/provider, and accumulates the LLM-round count. Allocated once per evaluation by
     * {@link #begin}; mutated only by the sequential reactive chain of a single evaluation.
     */
    private static final class EvaluationTrace {
        private final UUID traceId;
        private final String evaluatedIdKey;
        private final String evaluatedId;
        private final UUID evaluatedProjectId;
        private final String projectName;
        private final String evaluatedName;
        private final JsonNode evaluatedInput;
        private final JsonNode evaluatedOutput;
        private final UUID ruleId;
        private final String ruleName;
        private final String modelName;
        private final String actualModel;
        private final String provider;
        private final String workspaceId;
        private final String userName;
        private final Instant startTime;
        private final AtomicInteger llmCallCount = new AtomicInteger();
        private final AtomicInteger toolCallCount = new AtomicInteger();
        private volatile boolean agentic;

        private EvaluationTrace(UUID traceId, EvaluatedSubject subject, UUID ruleId, String ruleName,
                String modelName, String actualModel, String provider, String workspaceId, String userName,
                Instant startTime) {
            this.traceId = traceId;
            this.evaluatedIdKey = subject.kind().idKey();
            this.evaluatedId = subject.id();
            this.evaluatedProjectId = subject.projectId();
            this.projectName = subject.projectName();
            this.evaluatedName = subject.name();
            this.evaluatedInput = previewNode(subject.input());
            this.evaluatedOutput = previewNode(subject.output());
            this.ruleId = ruleId;
            this.ruleName = ruleName;
            this.modelName = modelName;
            this.actualModel = actualModel;
            this.provider = provider;
            this.workspaceId = workspaceId;
            this.userName = userName;
            this.startTime = startTime;
        }

        public void markAgentic() {
            this.agentic = true;
        }

        private String mode() {
            return agentic ? MODE_AGENTIC_TOOLS : MODE_INLINE;
        }
    }

    /** Real recorder backed by a per-evaluation {@link EvaluationTrace}; writes the trace and spans. */
    private final class RealRecorder implements EvaluationRecorder {
        private final EvaluationTrace eval;

        private RealRecorder(EvaluationTrace eval) {
            this.eval = eval;
        }

        @Override
        public Mono<ChatResponse> recordLlmCall(ChatRequest request, Mono<ChatResponse> call) {
            return OnlineScoringTracePersistence.this.recordLlmCall(eval, request, call);
        }

        @Override
        public Mono<String> recordToolCall(String toolName, String arguments, Mono<String> execution) {
            return OnlineScoringTracePersistence.this.recordToolCall(eval, toolName, arguments, execution);
        }

        @Override
        public Mono<Void> recordPreparation(int fetchedSpanCount, int estimatedTokens, boolean agentic) {
            return OnlineScoringTracePersistence.this.createPreparationSpan(eval, fetchedSpanCount,
                    estimatedTokens, agentic);
        }

        @Override
        public Mono<Void> complete(List<? extends FeedbackScoreItem> scores) {
            return OnlineScoringTracePersistence.this.complete(eval, scores);
        }

        @Override
        public Mono<Void> fail(Throwable error) {
            return OnlineScoringTracePersistence.this.fail(eval, error);
        }
    }

    /**
     * Opens an evaluation recorder. Pure (no I/O): generates the parent trace id, resolves the
     * model/provider once and captures the start time. Spans and the parent trace are written later
     * by {@link RealRecorder#recordLlmCall} and {@link RealRecorder#complete}/{@link RealRecorder#fail}.
     */
    public EvaluationRecorder begin(@NonNull EvaluatedSubject subject, @NonNull UUID ruleId, String ruleName,
            @NonNull String modelName, @NonNull String workspaceId, @NonNull String userName) {
        var resolvedModelInfo = llmProviderFactory.getResolvedModelInfo(modelName);
        var eval = new EvaluationTrace(idGenerator.generateId(), subject, ruleId, ruleName, modelName,
                resolvedModelInfo.actualModel(), resolvedModelInfo.provider(), workspaceId, userName, Instant.now());
        return new RealRecorder(eval);
    }

    /**
     * Wraps a single LLM call so its request/response (or error), token usage and timing are recorded
     * as one {@code llm} span under the evaluation's trace. On success the response is passed through
     * unchanged; on failure the error is recorded on the span and re-propagated.
     */
    private Mono<ChatResponse> recordLlmCall(@NonNull EvaluationTrace eval, @NonNull ChatRequest request,
            @NonNull Mono<ChatResponse> call) {
        return Mono.defer(() -> {
            var start = Instant.now();
            return call
                    .flatMap(response -> createLlmSpan(eval, request, response, null, start).thenReturn(response))
                    .onErrorResume(error -> createLlmSpan(eval, request, null, error, start)
                            .then(Mono.error(error)));
        });
    }

    /**
     * Wraps a single agentic tool execution so its arguments, result (or error) and timing are
     * recorded as one {@code tool} span under the evaluation's trace. The result is passed through
     * unchanged so the tool-call loop's budget/accounting is unaffected.
     */
    private Mono<String> recordToolCall(@NonNull EvaluationTrace eval, @NonNull String toolName,
            String arguments, @NonNull Mono<String> execution) {
        return Mono.defer(() -> {
            var start = Instant.now();
            return execution
                    .flatMap(result -> createToolSpan(eval, toolName, arguments, result, null, start)
                            .thenReturn(result))
                    .onErrorResume(error -> createToolSpan(eval, toolName, arguments, null, error, start)
                            .then(Mono.error(error)));
        });
    }

    /**
     * Finalizes the evaluation's parent trace on success, recording the produced feedback scores as
     * its output.
     */
    private Mono<Void> complete(@NonNull EvaluationTrace eval, @NonNull List<? extends FeedbackScoreItem> scores) {
        return createTrace(eval, buildScoresOutput(scores), null);
    }

    /**
     * Finalizes the evaluation's parent trace on failure, recording the error on the trace.
     */
    private Mono<Void> fail(@NonNull EvaluationTrace eval, @NonNull Throwable error) {
        return createTrace(eval, null, toErrorInfo(error));
    }

    private Mono<Void> createLlmSpan(EvaluationTrace eval, ChatRequest request, ChatResponse response,
            Throwable error, Instant start) {
        eval.llmCallCount.incrementAndGet();

        var spanBuilder = Span.builder()
                .id(idGenerator.generateId())
                .traceId(eval.traceId)
                .projectName(eval.projectName)
                .type(SpanType.llm)
                .name(SPAN_NAME)
                .startTime(start)
                .endTime(Instant.now())
                .input(buildMessagesInput(request))
                .model(eval.actualModel)
                .provider(eval.provider)
                .source(Source.EVALUATOR);

        if (response != null) {
            spanBuilder.output(buildResponseOutput(response))
                    .usage(extractUsage(response));
        }
        if (error != null) {
            spanBuilder.errorInfo(toErrorInfo(error));
        }

        return create(spanService.create(spanBuilder.build()), eval, "span");
    }

    private Mono<Void> createToolSpan(EvaluationTrace eval, String toolName, String arguments, String result,
            Throwable error, Instant start) {
        eval.toolCallCount.incrementAndGet();

        var input = JsonUtils.createObjectNode();
        if (arguments != null) {
            input.put("arguments", arguments);
        }

        var spanBuilder = Span.builder()
                .id(idGenerator.generateId())
                .traceId(eval.traceId)
                .projectName(eval.projectName)
                .type(SpanType.tool)
                .name(toolName)
                .startTime(start)
                .endTime(Instant.now())
                .input(input)
                .source(Source.EVALUATOR);

        if (result != null) {
            var output = JsonUtils.createObjectNode();
            output.put("result", result);
            spanBuilder.output(output);
        }
        if (error != null) {
            spanBuilder.errorInfo(toErrorInfo(error));
        }

        return create(spanService.create(spanBuilder.build()), eval, "tool span");
    }

    private Mono<Void> createPreparationSpan(EvaluationTrace eval, int fetchedSpanCount, int estimatedTokens,
            boolean agentic) {
        // The preparation phase decides inline vs agentic-tools mode; record it once here so the
        // parent trace's mode metadata matches this span's reported mode.
        if (agentic) {
            eval.markAgentic();
        }

        // Put the evaluated entity's own input/output on the span's input/output so the UI renders
        // them in "pretty" mode exactly as on the source trace (chat, text, etc.) instead of a raw
        // JSON blob; the evaluation bookkeeping (ids, model, fetch/size/mode) goes to span metadata.
        var metadata = JsonUtils.createObjectNode();
        metadata.put(eval.evaluatedIdKey, eval.evaluatedId);
        if (eval.evaluatedProjectId != null) {
            metadata.put("evaluated_project_id", eval.evaluatedProjectId.toString());
        }
        if (eval.evaluatedName != null) {
            metadata.put("evaluated_name", eval.evaluatedName);
        }
        metadata.put("model", eval.modelName);
        metadata.put("fetched_span_count", fetchedSpanCount);
        metadata.put("estimated_tokens", estimatedTokens);
        metadata.put("mode", agentic ? MODE_AGENTIC_TOOLS : MODE_INLINE);

        var spanBuilder = Span.builder()
                .id(idGenerator.generateId())
                .traceId(eval.traceId)
                .projectName(eval.projectName)
                .type(SpanType.general)
                .name(PREPARE_SPAN_NAME)
                .startTime(eval.startTime)
                .endTime(Instant.now())
                .metadata(metadata)
                .source(Source.EVALUATOR);

        if (eval.evaluatedInput != null) {
            spanBuilder.input(eval.evaluatedInput);
        }
        if (eval.evaluatedOutput != null) {
            spanBuilder.output(eval.evaluatedOutput);
        }

        return create(spanService.create(spanBuilder.build()), eval, "preparation span");
    }

    private Mono<Void> createTrace(EvaluationTrace eval, ObjectNode output, ErrorInfo errorInfo) {
        var input = JsonUtils.createObjectNode();
        input.put(eval.evaluatedIdKey, eval.evaluatedId);
        if (eval.evaluatedProjectId != null) {
            input.put("evaluated_project_id", eval.evaluatedProjectId.toString());
        }
        input.put("model", eval.modelName);

        var metadata = JsonUtils.createObjectNode();
        metadata.put("created_from", "online_evaluation");
        metadata.put("rule_id", eval.ruleId.toString());
        if (eval.ruleName != null) {
            metadata.put("rule_name", eval.ruleName);
        }
        metadata.put(eval.evaluatedIdKey, eval.evaluatedId);
        metadata.put("model", eval.modelName);
        metadata.put("mode", eval.mode());
        metadata.put("llm_call_count", eval.llmCallCount.get());
        metadata.put("tool_call_count", eval.toolCallCount.get());

        var traceBuilder = Trace.builder()
                .id(eval.traceId)
                .projectName(eval.projectName)
                .name(TRACE_NAME)
                .startTime(eval.startTime)
                .endTime(Instant.now())
                .input(input)
                .metadata(metadata)
                .source(Source.EVALUATOR)
                .visibilityMode(VisibilityMode.HIDDEN);

        if (output != null) {
            traceBuilder.output(output);
        }
        if (errorInfo != null) {
            traceBuilder.errorInfo(errorInfo);
        }

        return create(traceService.create(traceBuilder.build()), eval, "trace");
    }

    private Mono<Void> create(Mono<UUID> create, EvaluationTrace eval, String entity) {
        return create
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, eval.userName)
                        .put(RequestContext.WORKSPACE_ID, eval.workspaceId))
                .doOnError(throwable -> log.warn(
                        "Failed to persist online-evaluation monitoring '{}' for rule '{}', evaluated traceId '{}'",
                        entity, eval.ruleId, eval.evaluatedId, throwable))
                .onErrorComplete()
                .then();
    }

    private static Map<String, Integer> extractUsage(ChatResponse response) {
        var tokenUsage = response.tokenUsage();
        if (tokenUsage == null) {
            return null;
        }
        var usage = new HashMap<String, Integer>();
        if (tokenUsage.inputTokenCount() != null) {
            usage.put("prompt_tokens", tokenUsage.inputTokenCount());
        }
        if (tokenUsage.outputTokenCount() != null) {
            usage.put("completion_tokens", tokenUsage.outputTokenCount());
        }
        if (tokenUsage.totalTokenCount() != null) {
            usage.put("total_tokens", tokenUsage.totalTokenCount());
        }
        addCacheTokens(tokenUsage, usage);
        return usage.isEmpty() ? null : usage;
    }

    /**
     * Records provider-reported prompt-cache token counts under the keys
     * {@link com.comet.opik.domain.cost.SpanCostCalculator} prices: cache-read tokens are billed at a
     * reduced rate and cache-creation tokens at a surcharge. No-op for providers/responses without
     * cache usage, so the span's cost is unchanged when caching is off (the current default).
     */
    private static void addCacheTokens(TokenUsage tokenUsage, Map<String, Integer> usage) {
        switch (tokenUsage) {
            case AnthropicTokenUsage anthropic -> {
                putPositive(usage, "cache_creation_input_tokens", anthropic.cacheCreationInputTokens());
                putPositive(usage, "cache_read_input_tokens", anthropic.cacheReadInputTokens());
            }
            // OpenAI-family providers (OpenAI, OpenRouter, custom-llm, Ollama, free) all return this type.
            case OpenAiTokenUsage openai -> {
                if (openai.inputTokensDetails() != null) {
                    putPositive(usage, "cache_read_input_tokens", openai.inputTokensDetails().cachedTokens());
                }
            }
            // Gemini reports the context-cache hit as cachedContentTokenCount, which the Google cost
            // path subtracts from prompt_tokens and prices at the cache-read rate.
            case GoogleAiGeminiTokenUsage gemini ->
                putPositive(usage, "cache_read_input_tokens", gemini.cachedContentTokenCount());
            default -> {
            }
        }
    }

    private static void putPositive(Map<String, Integer> usage, String key, Integer value) {
        if (value != null && value > 0) {
            usage.put(key, value);
        }
    }

    private static ObjectNode buildMessagesInput(ChatRequest request) {
        var input = JsonUtils.createObjectNode();
        ArrayNode messages = JsonUtils.createArrayNode();
        if (request.messages() != null) {
            for (var message : request.messages()) {
                var node = JsonUtils.createObjectNode();
                node.put("role", message.type().name().toLowerCase());
                node.put("content", messageText(message));
                messages.add(node);
            }
        }
        input.set("messages", messages);
        return input;
    }

    private static ObjectNode buildResponseOutput(ChatResponse response) {
        var output = JsonUtils.createObjectNode();
        var aiMessage = response.aiMessage();
        output.put("output", aiMessage.text() == null ? "" : aiMessage.text());
        if (aiMessage.hasToolExecutionRequests()) {
            ArrayNode toolCalls = JsonUtils.createArrayNode();
            aiMessage.toolExecutionRequests().forEach(toolCall -> {
                var node = JsonUtils.createObjectNode();
                node.put("name", toolCall.name());
                node.put("arguments", toolCall.arguments());
                toolCalls.add(node);
            });
            output.set("tool_calls", toolCalls);
        }
        return output;
    }

    private static ObjectNode buildScoresOutput(List<? extends FeedbackScoreItem> scores) {
        var output = JsonUtils.createObjectNode();
        ArrayNode scoresArray = JsonUtils.createArrayNode();
        for (var score : scores) {
            var node = JsonUtils.createObjectNode();
            node.put("name", score.name());
            if (score.value() != null) {
                node.put("value", score.value());
            }
            if (score.reason() != null) {
                node.put("reason", score.reason());
            }
            scoresArray.add(node);
        }
        output.set("scores", scoresArray);
        return output;
    }

    private static String messageText(ChatMessage message) {
        return switch (message) {
            case SystemMessage systemMessage -> systemMessage.text();
            case UserMessage userMessage -> userMessage.contents().stream()
                    .filter(TextContent.class::isInstance)
                    .map(content -> ((TextContent) content).text())
                    .collect(Collectors.joining("\n"));
            case AiMessage aiMessage -> aiMessage.text() == null ? "" : aiMessage.text();
            case ToolExecutionResultMessage toolResult -> toolResult.text();
            default -> String.valueOf(message);
        };
    }

    private static ErrorInfo toErrorInfo(Throwable error) {
        var rootCause = ExceptionUtils.getRootCause(error);
        var cause = rootCause != null ? rootCause : error;
        var message = cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName();
        return ErrorInfo.builder()
                .exceptionType(cause.getClass().getSimpleName())
                .message(message)
                .traceback(ExceptionUtils.getStackTrace(error))
                .build();
    }

    /**
     * Preview of an evaluated entity's input/output for the prepare_evaluation span, so a viewer can
     * tell what the evaluation is assessing without opening the evaluated trace. Small payloads are
     * kept structurally intact (so the UI renders them in pretty mode just like the source trace);
     * payloads larger than {@link #EVALUATED_PREVIEW_CHARS} fall back to a truncated text node to keep
     * the monitoring span small.
     */
    private static JsonNode previewNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        // Measure and slice the SAME representation: the raw text for string nodes (no JSON quoting),
        // the serialized JSON otherwise. Using toString() to measure but asText() to slice could slice
        // a string shorter than the cap and throw IndexOutOfBounds.
        String text = node.isTextual() ? node.asText() : node.toString();
        if (text.length() <= EVALUATED_PREVIEW_CHARS) {
            return node;
        }
        return TextNode.valueOf(text.substring(0, EVALUATED_PREVIEW_CHARS) + "…[truncated]");
    }
}
