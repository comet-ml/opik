package com.comet.opik.domain.evaluation;

import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.Source;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.VisibilityMode;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanType;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.observability.ObservabilityContext;
import com.comet.opik.domain.observability.ObservabilityTraceRecorder;
import com.comet.opik.infrastructure.ResponseFormattingConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
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
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.exception.ExceptionUtils;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Online-evaluation adapter on top of the {@link ObservabilityTraceRecorder} core: maps the LLM-as-judge
 * loop into the monitoring trace/span model (OPIK-6994). One {@link Trace} is created per evaluation
 * and one {@code llm} {@link Span} per LLM round-trip (the initial call plus every agentic-tools round
 * and the wrap-up). Spans carry token usage and the resolved model/provider, so the existing
 * span→trace cost aggregation computes the evaluation's cost with no extra work.
 * <p>
 * Traces/spans carry {@link Source#EVALUATOR} and the trace is {@link VisibilityMode#HIDDEN}, so they
 * are kept out of the default traces view and aggregations like other non-SDK/hidden traces, and the
 * online-scoring samplers skip them automatically: {@code Source.isLoggingSource(EVALUATOR)} is false,
 * so the trace sampler only scores them if they carry {@code selected_rule_ids} (they never do) and
 * the span sampler filters them out. The engine therefore never evaluates its own monitoring traces.
 * <p>
 * This class only builds the trace/span objects; the {@link ObservabilityTraceRecorder} guarantees the
 * writes never disrupt the scoring flow that produced them. The {@link EvaluationRecorder} contract
 * therefore exposes the lifecycle as {@code void} fire-and-forget calls and the per-round wrappers as
 * pass-through taps.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OnlineEvaluationRecorder {

    private static final String TRACE_NAME = "online_evaluation";
    private static final String SPAN_NAME = "llm_call";
    private static final String PREPARE_SPAN_NAME = "prepare_evaluation";

    private static final String MODE_INLINE = "inline";
    private static final String MODE_AGENTIC_TOOLS = "agentic_tools";

    private final @NonNull ObservabilityTraceRecorder observabilityRecorder;
    private final @NonNull LlmProviderFactory llmProviderFactory;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull @Config("responseFormatting") ResponseFormattingConfig responseFormattingConfig;

    /** Real recorder backed by a per-evaluation {@link EvaluationContext}; writes the trace and spans. */
    private final class RealRecorder implements EvaluationRecorder {
        private final EvaluationContext eval;

        private RealRecorder(EvaluationContext eval) {
            this.eval = eval;
        }

        @Override
        public Mono<ChatResponse> recordLlmCall(ChatRequest request, Mono<ChatResponse> call) {
            return Mono.defer(() -> {
                var start = Instant.now();
                return observabilityRecorder.recordSpan(call, eval.observabilityContext(),
                        response -> buildLlmSpan(eval, request, response, null, start),
                        error -> buildLlmSpan(eval, request, null, error, start));
            });
        }

        @Override
        public Mono<String> recordToolCall(String toolName, String arguments, Mono<String> execution) {
            return Mono.defer(() -> {
                var start = Instant.now();
                return observabilityRecorder.recordSpan(execution, eval.observabilityContext(),
                        result -> buildToolSpan(eval, toolName, arguments, result, null, start),
                        error -> buildToolSpan(eval, toolName, arguments, null, error, start));
            });
        }

        @Override
        public void recordPreparation(int fetchedSpanCount, int estimatedTokens, boolean agentic) {
            // The preparation phase decides inline vs agentic-tools mode; recorded on this span.
            observabilityRecorder.recordSpan(eval.observabilityContext(),
                    () -> buildPreparationSpan(eval, fetchedSpanCount, estimatedTokens, agentic));
        }

        @Override
        public <T extends FeedbackScoreItem> Mono<List<T>> monitor(Mono<List<T>> scoring) {
            return scoring
                    .doOnNext(scores -> observabilityRecorder.recordTrace(eval.observabilityContext(),
                            () -> buildTrace(eval, buildScoresOutput(scores), null)))
                    .doOnError(error -> observabilityRecorder.recordTrace(eval.observabilityContext(),
                            () -> buildTrace(eval, null, toErrorInfo(error))));
        }
    }

    /**
     * Opens an evaluation recorder. Pure (no I/O): generates the parent trace id, resolves the
     * model/provider once and captures the start time. Spans and the parent trace are written later,
     * fire-and-forget, by the {@link RealRecorder}.
     */
    public EvaluationRecorder begin(@NonNull EvaluatedSubject subject, @NonNull UUID ruleId, String ruleName,
            @NonNull String modelName, @NonNull String workspaceId, @NonNull String userName) {
        var resolvedModelInfo = llmProviderFactory.getResolvedModelInfo(modelName);
        var eval = EvaluationContext.builder()
                .traceId(idGenerator.generateId())
                .evaluatedIdKey(subject.kind().getIdKey())
                .evaluatedId(subject.id())
                .evaluatedProjectId(subject.projectId())
                .projectName(subject.projectName())
                .evaluatedName(subject.name())
                .evaluatedInput(previewNode(subject.input()))
                .evaluatedOutput(previewNode(subject.output()))
                .ruleId(ruleId)
                .ruleName(ruleName)
                .modelName(modelName)
                .actualModel(resolvedModelInfo.actualModel())
                .provider(resolvedModelInfo.provider())
                .observabilityContext(new ObservabilityContext(workspaceId, userName))
                .startTime(Instant.now())
                .build();
        return new RealRecorder(eval);
    }

    private Span buildLlmSpan(EvaluationContext eval, ChatRequest request, ChatResponse response, Throwable error,
            Instant start) {
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

        return spanBuilder.build();
    }

    private Span buildToolSpan(EvaluationContext eval, String toolName, String arguments, String result,
            Throwable error, Instant start) {
        Map<String, Object> input = new LinkedHashMap<>();
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
                .input(JsonUtils.valueToTree(input))
                .source(Source.EVALUATOR);

        if (result != null) {
            spanBuilder.output(JsonUtils.valueToTree(Map.of("result", result)));
        }
        if (error != null) {
            spanBuilder.errorInfo(toErrorInfo(error));
        }

        return spanBuilder.build();
    }

    private Span buildPreparationSpan(EvaluationContext eval, int fetchedSpanCount, int estimatedTokens,
            boolean agentic) {
        // Put the evaluated entity's own input/output on the span's input/output so the UI renders
        // them in "pretty" mode exactly as on the source trace (chat, text, etc.) instead of a raw
        // JSON blob; the evaluation bookkeeping (ids, model, fetch/size/mode) goes to span metadata.
        Map<String, Object> metadata = new LinkedHashMap<>();
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
                .metadata(JsonUtils.valueToTree(metadata))
                .source(Source.EVALUATOR);

        if (eval.evaluatedInput != null) {
            spanBuilder.input(eval.evaluatedInput);
        }
        if (eval.evaluatedOutput != null) {
            spanBuilder.output(eval.evaluatedOutput);
        }

        return spanBuilder.build();
    }

    private Trace buildTrace(EvaluationContext eval, JsonNode output, ErrorInfo errorInfo) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put(eval.evaluatedIdKey, eval.evaluatedId);
        if (eval.evaluatedProjectId != null) {
            input.put("evaluated_project_id", eval.evaluatedProjectId.toString());
        }
        input.put("model", eval.modelName);

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("created_from", "online_evaluation");
        metadata.put("rule_id", eval.ruleId.toString());
        if (eval.ruleName != null) {
            metadata.put("rule_name", eval.ruleName);
        }
        metadata.put(eval.evaluatedIdKey, eval.evaluatedId);
        metadata.put("model", eval.modelName);

        var traceBuilder = Trace.builder()
                .id(eval.traceId)
                .projectName(eval.projectName)
                .name(TRACE_NAME)
                .startTime(eval.startTime)
                .endTime(Instant.now())
                .input(JsonUtils.valueToTree(input))
                .metadata(JsonUtils.valueToTree(metadata))
                .source(Source.EVALUATOR)
                .visibilityMode(VisibilityMode.HIDDEN);

        if (output != null) {
            traceBuilder.output(output);
        }
        if (errorInfo != null) {
            traceBuilder.errorInfo(errorInfo);
        }

        return traceBuilder.build();
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

    private static JsonNode buildMessagesInput(ChatRequest request) {
        var messages = request.messages() == null
                ? List.<JudgeMessage>of()
                : request.messages().stream()
                        .map(message -> new JudgeMessage(message.type().name().toLowerCase(), messageText(message)))
                        .toList();
        return JsonUtils.valueToTree(new MessagesInput(messages));
    }

    private static JsonNode buildResponseOutput(ChatResponse response) {
        var aiMessage = response.aiMessage();
        var toolCalls = aiMessage.hasToolExecutionRequests()
                ? aiMessage.toolExecutionRequests().stream()
                        .map(toolCall -> new ToolCallView(toolCall.name(), toolCall.arguments()))
                        .toList()
                : null;
        return JsonUtils.valueToTree(new ResponseOutput(aiMessage.text() == null ? "" : aiMessage.text(), toolCalls));
    }

    private static JsonNode buildScoresOutput(List<? extends FeedbackScoreItem> scores) {
        var scoreViews = scores.stream()
                .map(score -> new ScoreView(score.name(), score.value(), score.reason()))
                .toList();
        return JsonUtils.valueToTree(new ScoresOutput(scoreViews));
    }

    // Fixed-shape span/trace input & output, serialized via the snake_case + non-null mapper
    // (so toolCalls -> tool_calls and null fields are omitted), instead of hand-built JSON nodes.
    private record MessagesInput(List<JudgeMessage> messages) {
    }

    private record JudgeMessage(String role, String content) {
    }

    private record ResponseOutput(String output, List<ToolCallView> toolCalls) {
    }

    private record ToolCallView(String name, String arguments) {
    }

    private record ScoresOutput(List<ScoreView> scores) {
    }

    private record ScoreView(String name, BigDecimal value, String reason) {
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
     * payloads larger than the Opik-wide {@code responseFormatting.truncationSize} (the same field
     * truncation limit used for all traces) fall back to a truncated text node.
     */
    private JsonNode previewNode(JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        int previewMaxChars = responseFormattingConfig.getTruncationSize();
        // Measure against the SERIALIZED form so a heavily-escaped string (quotes/backslashes) can't slip
        // past the cap once rendered as JSON. Slice the raw text for string nodes and clamp the index to
        // its length, so we never slice past a string shorter than the cap (which would throw IOOBE).
        String serialized = node.toString();
        if (serialized.length() <= previewMaxChars) {
            return node;
        }
        String raw = node.isTextual() ? node.asText() : serialized;
        int end = Math.min(previewMaxChars, raw.length());
        return TextNode.valueOf(raw.substring(0, end) + "…[truncated]");
    }
}
