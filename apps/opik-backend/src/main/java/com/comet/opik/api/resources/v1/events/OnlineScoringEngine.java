package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.PromptType;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanForLlm;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.evaluators.LlmAsJudgeMessageContent;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.resources.v1.events.tools.StringTruncator;
import com.comet.opik.domain.evaluators.python.TraceThreadPythonEvaluatorRequest;
import com.comet.opik.domain.llm.structuredoutput.StructuredOutputStrategy;
import com.comet.opik.infrastructure.log.LogContextAware;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TemplateParseUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.google.api.gax.rpc.InvalidArgumentException;
import com.google.common.annotations.VisibleForTesting;
import com.jayway.jsonpath.JsonPath;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;
import org.slf4j.Logger;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge.TraceThreadLlmAsJudgeCode;

@UtilityClass
@Slf4j
public class OnlineScoringEngine {

    static final String SCORE_FIELD_NAME = "score";
    static final String REASON_FIELD_NAME = "reason";

    private static final String SPANS_VARIABLE_NAME = "spans";
    private static final String TRACE_VARIABLE_NAME = "trace";
    private static final String SPAN_VARIABLE_NAME = "span";

    private static final Set<String> SENTINEL_VARIABLE_VALUES = Set.of(
            SPANS_VARIABLE_NAME, TRACE_VARIABLE_NAME, SPAN_VARIABLE_NAME);

    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.getMapper();

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile(
            "```(?:json)?\\s*(\\{.*?})\\s*```", Pattern.DOTALL);

    private static final Comparator<Span> BY_SPAN_START_TIME = Comparator
            .comparing(Span::startTime, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Prepare a request to a LLM-as-Judge evaluator (a ChatLanguageModel) rendering
     * the template messages with
     * Trace variables and with the proper structured output format.
     *
     * @param evaluatorCode the LLM-as-Judge 'code'
     * @param trace         the sampled Trace to be scored
     * @return a request to trigger to any supported provider with a
     *         ChatLanguageModel
     */
    public static ChatRequest prepareLlmRequest(
            @NonNull LlmAsJudgeCode evaluatorCode, Trace trace,
            StructuredOutputStrategy structuredOutputStrategy, @NonNull List<Span> spans) {
        return prepareLlmRequest(evaluatorCode, trace, structuredOutputStrategy, PromptType.MUSTACHE, spans);
    }

    public static ChatRequest prepareLlmRequest(
            @NonNull LlmAsJudgeCode evaluatorCode, Trace trace,
            StructuredOutputStrategy structuredOutputStrategy, @NonNull PromptType promptType,
            @NonNull List<Span> spans) {
        return prepareLlmRequest(evaluatorCode, trace, structuredOutputStrategy, promptType, spans, null);
    }

    public static ChatRequest prepareLlmRequest(
            @NonNull LlmAsJudgeCode evaluatorCode, Trace trace,
            StructuredOutputStrategy structuredOutputStrategy, @NonNull PromptType promptType,
            @NonNull List<Span> spans, String traceStructureJson) {
        Map<String, String> replacements = toReplacements(evaluatorCode.variables(), trace);
        injectSpansIntoReplacements(replacements, evaluatorCode.variables(),
                evaluatorCode.messages(), promptType, spans);
        injectTraceIntoReplacements(replacements, evaluatorCode.variables(),
                evaluatorCode.messages(), promptType, traceStructureJson);
        var renderedMessages = renderMessagesWithReplacements(evaluatorCode.messages(), replacements, promptType);
        return buildChatRequest(renderedMessages, evaluatorCode.schema(), structuredOutputStrategy);
    }

    /**
     * Variant of {@link #prepareLlmRequest(LlmAsJudgeCode, Trace, StructuredOutputStrategy, PromptType, List)}
     * that caps sentinel variable substitutions (e.g. {@code {{spans}}}) at {@code maxReplacementChars}
     * while leaving user-mapped variables (e.g. {@code output}, {@code expected_output}) uncapped.
     *
     * <p>User-mapped variables are the scoring data the judge needs to evaluate — capping them forces
     * the LLM to drill down via tools, which is non-deterministic and produces intermittent
     * "Insufficient data" failures (OPIK-7110). Sentinel variables are structural context that the
     * judge can optionally drill into via {@code read}/{@code jq} tools.
     */
    public static ChatRequest prepareLlmRequest(
            @NonNull LlmAsJudgeCode evaluatorCode, Trace trace,
            StructuredOutputStrategy structuredOutputStrategy, @NonNull PromptType promptType,
            int maxReplacementChars, @NonNull String drillDownHint, @NonNull List<Span> spans) {
        return prepareLlmRequest(evaluatorCode, trace, structuredOutputStrategy, promptType,
                maxReplacementChars, drillDownHint, spans, null);
    }

    public static ChatRequest prepareLlmRequest(
            @NonNull LlmAsJudgeCode evaluatorCode, Trace trace,
            StructuredOutputStrategy structuredOutputStrategy, @NonNull PromptType promptType,
            int maxReplacementChars, @NonNull String drillDownHint, @NonNull List<Span> spans,
            String traceStructureJson) {
        Map<String, String> replacements = toReplacements(evaluatorCode.variables(), trace);
        injectSpansIntoReplacements(replacements, evaluatorCode.variables(),
                evaluatorCode.messages(), promptType, spans);
        injectTraceIntoReplacements(replacements, evaluatorCode.variables(),
                evaluatorCode.messages(), promptType, traceStructureJson);
        Set<String> userMappedKeys = userMappedVariableKeys(evaluatorCode.variables());
        Map<String, String> capped = capReplacements(replacements, maxReplacementChars,
                drillDownHint, userMappedKeys);
        var renderedMessages = renderMessagesWithReplacements(evaluatorCode.messages(), capped, promptType);
        return buildChatRequest(renderedMessages, evaluatorCode.schema(), structuredOutputStrategy);
    }

    /**
     * Whether the rule needs the trace's spans list rendered into the prompt — opt-in via the
     * {@code "spans"} sentinel (see {@link #referencesSpecialVariable} for the two opt-in shapes).
     * Used by the trace scorer to opt-in to the {@code spanService.getByTraceIds(...)} fetch for
     * inline LLM-as-judge evaluations whose template references {@code {{spans}}}.
     */
    public static boolean templateReferencesSpans(
            @NonNull List<LlmAsJudgeMessage> messages,
            @NonNull Map<String, String> variables,
            @NonNull PromptType promptType) {
        return referencesSpecialVariable(messages, variables, promptType, SPANS_VARIABLE_NAME);
    }

    /**
     * Whether the rule references the {@code {{trace}}} structure variable — the declarative signal that
     * the judge needs the agentic-tools loop (it injects the trace id, span ids and attachment
     * {@code file_name}s into the prompt so the judge can call {@code get_attachment} without fabricating
     * ids). Opt-in via the {@code "trace"} sentinel (see {@link #referencesSpecialVariable}).
     */
    public static boolean templateReferencesTraceStructure(
            @NonNull List<LlmAsJudgeMessage> messages,
            @NonNull Map<String, String> variables,
            @NonNull PromptType promptType) {
        return referencesSpecialVariable(messages, variables, promptType, TRACE_VARIABLE_NAME);
    }

    /**
     * Whether a span-level rule references the {@code {{span}}} structure variable — the declarative
     * signal that the span judge needs the agentic-tools loop (it injects the span id + the span's own
     * attachment {@code file_name}s so the judge can call {@code get_attachment(type=span, ...)} without
     * fabricating ids). Opt-in via the {@code "span"} sentinel (see {@link #referencesSpecialVariable});
     * distinct from the trace-level {@code {{spans}}} list sentinel.
     */
    public static boolean templateReferencesSpanStructure(
            @NonNull List<LlmAsJudgeMessage> messages,
            @NonNull Map<String, String> variables,
            @NonNull PromptType promptType) {
        return referencesSpecialVariable(messages, variables, promptType, SPAN_VARIABLE_NAME);
    }

    /**
     * Whether a rule opts into the special variable named {@code sentinel} — the shared detection behind
     * {@code {{spans}}}, {@code {{trace}}} and {@code {{span}}}. Two opt-in shapes:
     * <ul>
     *   <li>Sentinel-valued variable: any entry in {@code variables} whose value is the bare sentinel
     *       string (no JSONPath prefix) — what the FE writes when the user types {@code {{<sentinel>}}}.
     *   <li>Direct template reference: a message references {@code {{<sentinel>}}} (per {@code promptType})
     *       without the variables map binding it to a custom path, so an explicit user mapping wins.
     * </ul>
     */
    private static boolean referencesSpecialVariable(
            List<LlmAsJudgeMessage> messages, Map<String, String> variables, PromptType promptType,
            String sentinel) {
        return variables.containsValue(sentinel)
                || messagesReferenceSpecialVariableDirectly(messages, variables, promptType, sentinel);
    }

    /**
     * True when at least one message template references {@code {{<sentinel>}}} (per {@code promptType})
     * AND the variables map does not bind {@code sentinel} to a custom path. Walks both message shapes —
     * the simple-string {@code content} and the multimodal {@code contentArray} text parts (via
     * {@link #renderableTextOf}); scanning only {@code content} would miss references in multimodal
     * prompts, leaving the rendered text part unsubstituted because the opt-in never fires.
     */
    private static boolean messagesReferenceSpecialVariableDirectly(
            List<LlmAsJudgeMessage> messages, Map<String, String> variables, PromptType promptType,
            String sentinel) {
        if (variables.containsKey(sentinel)) {
            return false;
        }
        return messages.stream()
                .filter(Objects::nonNull)
                .flatMap(OnlineScoringEngine::renderableTextOf)
                .anyMatch(text -> TemplateParseUtils.extractVariables(text, promptType).contains(sentinel));
    }

    /**
     * Stream of all variable-substitutable text in a message: the simple {@code content}
     * string when present, otherwise each non-null {@code text} part inside {@code contentArray}.
     * Mirrors what the renderer would substitute into — anything we should scan for
     * {@code {{spans}}} references must also be scanned by this helper, or detection
     * drifts from rendering.
     */
    private static Stream<String> renderableTextOf(LlmAsJudgeMessage message) {
        if (message.isStringContent()) {
            return Stream.of(message.content());
        }
        if (message.isStructuredContent()) {
            return message.contentArray().stream()
                    .filter(Objects::nonNull)
                    .map(LlmAsJudgeMessageContent::text)
                    .filter(Objects::nonNull);
        }
        return Stream.empty();
    }

    /**
     * Replace any variable mapped to the {@code "spans"} sentinel (and the implicit {@code {{spans}}}
     * reference) with the JSON-serialized spans list (parent→child tree, siblings sorted by start_time).
     * See {@link #injectSpecialVariable} for the shared substitution mechanics; the tree is serialized
     * lazily, only when the sentinel is actually referenced.
     *
     * <p>An empty spans list still triggers the rewrite (rendering as {@code "[]"}).
     * <strong>Intentionally not gated by {@code isAgenticToolsEnabled}</strong>: when the toggle is off,
     * the scorer skips the spans fetch and threads an empty list here, which still rewrites
     * sentinel-mapped variables to {@code "[]"}. Gating this would resurrect the bare-word leak for rules
     * whose variables map still carries the sentinel from before the toggle flipped. See
     * {@code OnlineScoringLlmAsJudgeScorer.shouldFetchSpans} for the full toggle-semantics rationale.
     */
    private static void injectSpansIntoReplacements(
            Map<String, String> replacements, Map<String, String> variables,
            List<LlmAsJudgeMessage> messages, PromptType promptType, List<Span> spans) {
        injectSpecialVariable(replacements, variables, messages, promptType, SPANS_VARIABLE_NAME,
                () -> serializeSpansTree(spans));
    }

    /**
     * Replace the {@code "trace"} sentinel with the pre-built trace structure JSON (built upstream in the
     * scorer's reactive attachment fetch). A null structure renders as {@code "{}"} so the variable never
     * leaks the bare word "trace". See {@link #injectSpecialVariable}.
     */
    private static void injectTraceIntoReplacements(
            Map<String, String> replacements, Map<String, String> variables,
            List<LlmAsJudgeMessage> messages, PromptType promptType, String traceStructureJson) {
        injectSpecialVariable(replacements, variables, messages, promptType, TRACE_VARIABLE_NAME,
                () -> traceStructureJson != null ? traceStructureJson : "{}");
    }

    /**
     * Replace the {@code "span"} sentinel with the pre-built span structure JSON. A null structure renders
     * as {@code "{}"}. Span-level mirror of {@link #injectTraceIntoReplacements}; see
     * {@link #injectSpecialVariable}.
     */
    private static void injectSpanIntoReplacements(
            Map<String, String> replacements, Map<String, String> variables,
            List<LlmAsJudgeMessage> messages, PromptType promptType, String spanStructureJson) {
        injectSpecialVariable(replacements, variables, messages, promptType, SPAN_VARIABLE_NAME,
                () -> spanStructureJson != null ? spanStructureJson : "{}");
    }

    /**
     * Shared substitution for a sentinel-named special variable. Mutates {@code replacements} in place:
     * every variable whose source path is {@code sentinel} (and, for an implicit {@code {{<sentinel>}}}
     * template reference with no binding, the sentinel key itself) is set to {@code value}. No-op — and
     * {@code value} is never invoked — when nothing references the sentinel, so callers can defer
     * expensive value construction (e.g. serializing the spans tree) into the supplier.
     *
     * <p>An empty/placeholder value still triggers the rewrite (e.g. {@code "[]"} / {@code "{}"}): without
     * it, {@code toReplacements} would leave the bare sentinel as a literal and the prompt would render
     * the sentinel word instead. Also handles the implicit-reference case (template uses
     * {@code {{<sentinel>}}} but the variables map doesn't bind it), mirroring the FE auto-fill so
     * API-created rules behave the same without knowing the sentinel convention.
     */
    private static void injectSpecialVariable(
            Map<String, String> replacements, Map<String, String> variables,
            List<LlmAsJudgeMessage> messages, PromptType promptType, String sentinel,
            Supplier<String> value) {
        boolean sentinelMapped = variables.containsValue(sentinel);
        boolean templateOnly = messagesReferenceSpecialVariableDirectly(messages, variables, promptType, sentinel);
        if (!sentinelMapped && !templateOnly) {
            return;
        }
        String rendered = value.get();
        variables.forEach((name, path) -> {
            if (sentinel.equals(path)) {
                replacements.put(name, rendered);
            }
        });
        if (templateOnly) {
            replacements.put(sentinel, rendered);
        }
    }

    private static String serializeSpansTree(List<Span> spans) {
        // Project to SpanForLlm and reconstruct the parent→child hierarchy so the judge sees the call
        // tree, not a flat list. Drops audit metadata, feedback scores, comments, cost data — none help
        // the judge and all burn tokens. Siblings are sorted by start_time inside buildSpanTree.
        try {
            return OBJECT_MAPPER.writeValueAsString(buildSpanTree(spans));
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * Returns the set of replacement-map keys that correspond to user-mapped variables (trace
     * sections like {@code output}, {@code input.question}, or literal constants) as opposed to
     * sentinel variables (like {@code spans}). Used by {@link #capReplacements} to decide which
     * keys to leave uncapped.
     */
    @VisibleForTesting
    static Set<String> userMappedVariableKeys(Map<String, String> variables) {
        var keys = new HashSet<>(variables.keySet());
        keys.removeIf(k -> SENTINEL_VARIABLE_VALUES.contains(variables.get(k)));
        return keys;
    }

    /**
     * Caps replacement values at {@code maxReplacementChars}, skipping keys in
     * {@code uncappedKeys}. Pass {@code Set.of()} to cap everything. User-mapped scoring
     * variables should be uncapped — capping them forces non-deterministic tool drill-down
     * (OPIK-7110).
     */
    @VisibleForTesting
    static Map<String, String> capReplacements(Map<String, String> replacements,
            int maxReplacementChars, String drillDownHint, Set<String> uncappedKeys) {
        return replacements.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> uncappedKeys.contains(e.getKey())
                        ? e.getValue()
                        : StringTruncator.truncate(e.getValue(), maxReplacementChars, drillDownHint),
                (a, b) -> b,
                LinkedHashMap::new));
    }

    /**
     * Prepare a request to a LLM-as-Judge evaluator (a ChatLanguageModel) rendering
     * the template messages with Span variables and with the proper structured output format.
     *
     * @param evaluatorCode the LLM-as-Judge 'code'
     * @param span          the sampled Span to be scored
     * @return a request to trigger to any supported provider with a ChatLanguageModel
     */
    public static ChatRequest prepareSpanLlmRequest(
            @NonNull AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode evaluatorCode,
            @NonNull Span span,
            @NonNull StructuredOutputStrategy structuredOutputStrategy) {
        var renderedMessages = renderMessages(evaluatorCode.messages(), evaluatorCode.variables(), span);
        return buildChatRequest(renderedMessages, evaluatorCode.schema(), structuredOutputStrategy);
    }

    /**
     * Inline variant that injects the pre-built {@code {{span}}} structure (span id + the span's own
     * attachment {@code file_name}s) without capping — used by the span scorer when a rule references
     * {@code {{span}}} but the provider can't call tools, so the variable still renders the structure
     * instead of the bare word "span". Span templates always render with {@link PromptType#MUSTACHE}.
     */
    public static ChatRequest prepareSpanLlmRequest(
            @NonNull AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode evaluatorCode,
            @NonNull Span span,
            @NonNull StructuredOutputStrategy structuredOutputStrategy,
            String spanStructureJson) {
        Map<String, String> replacements = toReplacements(evaluatorCode.variables(), span);
        injectSpanIntoReplacements(replacements, evaluatorCode.variables(),
                evaluatorCode.messages(), PromptType.MUSTACHE, spanStructureJson);
        var renderedMessages = renderMessagesWithReplacements(evaluatorCode.messages(), replacements,
                PromptType.MUSTACHE);
        return buildChatRequest(renderedMessages, evaluatorCode.schema(), structuredOutputStrategy);
    }

    /**
     * Tool-mode variant of {@link #prepareSpanLlmRequest(AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode, Span, StructuredOutputStrategy)}
     * used by the span scorer's agentic-tools path: injects the pre-built {@code {{span}}} structure
     * (span id + the span's own attachment {@code file_name}s) and caps sentinel variable substitutions
     * (e.g. {@code {{span}}}) at {@code maxReplacementChars} while leaving user-mapped variables
     * uncapped (OPIK-7110). Span templates always render with {@link PromptType#MUSTACHE}.
     */
    public static ChatRequest prepareSpanLlmRequest(
            @NonNull AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode evaluatorCode,
            @NonNull Span span,
            @NonNull StructuredOutputStrategy structuredOutputStrategy,
            int maxReplacementChars, @NonNull String drillDownHint, String spanStructureJson) {
        Map<String, String> replacements = toReplacements(evaluatorCode.variables(), span);
        injectSpanIntoReplacements(replacements, evaluatorCode.variables(),
                evaluatorCode.messages(), PromptType.MUSTACHE, spanStructureJson);
        Set<String> userMappedKeys = userMappedVariableKeys(evaluatorCode.variables());
        Map<String, String> capped = capReplacements(replacements, maxReplacementChars,
                drillDownHint, userMappedKeys);
        var renderedMessages = renderMessagesWithReplacements(evaluatorCode.messages(), capped, PromptType.MUSTACHE);
        return buildChatRequest(renderedMessages, evaluatorCode.schema(), structuredOutputStrategy);
    }

    /**
     * Common implementation for building ChatRequest from rendered messages.
     * Extracted to reduce duplication between prepareLlmRequest, prepareSpanLlmRequest, and prepareThreadLlmRequest.
     */
    private static ChatRequest buildChatRequest(
            List<ChatMessage> renderedMessages,
            List<LlmAsJudgeOutputSchema> schema,
            StructuredOutputStrategy structuredOutputStrategy) {
        var chatRequestBuilder = ChatRequest.builder().messages(renderedMessages);
        return structuredOutputStrategy.apply(chatRequestBuilder, renderedMessages, schema).build();
    }

    /**
     * Prepare a request to a LLM-as-Judge evaluator (a ChatLanguageModel) rendering
     * the template messages with
     * Trace variables and with the proper structured output format.
     *
     * @param evaluatorCode the LLM-as-Judge 'code'
     * @param traces        the sampled traces from the trace threads to be scored
     * @return a request to trigger to any supported provider with a
     *         ChatLanguageModel
     */
    public static ChatRequest prepareThreadLlmRequest(
            @NonNull TraceThreadLlmAsJudgeCode evaluatorCode, @NonNull List<Trace> traces,
            @NonNull StructuredOutputStrategy structuredOutputStrategy,
            @NonNull List<Span> spans) {
        var renderedMessages = renderThreadMessages(evaluatorCode.messages(),
                Map.of(TraceThreadLlmAsJudgeCode.CONTEXT_VARIABLE_NAME, ""), traces, spans);
        return buildChatRequest(renderedMessages, evaluatorCode.schema(), structuredOutputStrategy);
    }

    /**
     * Variant for the agentic-tools branch: renders only a compact per-trace
     * <em>skeleton</em> for the thread (ids, names, durations, span counts) plus a
     * drill-down hint pointing at {@code read(type=trace, id=X)}. The model fetches
     * any specific trace's full content (and its spans) on demand via ReadTool —
     * the same lazy mechanism the trace-level path uses. Keeps the inline prompt
     * bounded even on threads with thousands of traces.
     *
     * <p>The {@code context} variable is replaced with the skeleton + drill-down
     * guidance so user-supplied prompt templates referencing {@code {{context}}}
     * keep working without modification.
     *
     * <p><strong>Precondition:</strong> all {@code evaluatorCode.messages()} must declare
     * string content. Multimodal templates aren't supported on this path —
     * {@link #renderThreadMessagesWithReplacement} throws on the first non-string entry.
     * Callers should detect multimodal templates upstream via
     * {@link #hasMultimodalTemplate(List)} and fall back to the inline path.
     */
    public static ChatRequest prepareThreadLlmRequestWithTools(
            @NonNull TraceThreadLlmAsJudgeCode evaluatorCode, @NonNull List<Trace> traces,
            @NonNull StructuredOutputStrategy structuredOutputStrategy) {
        String skeleton;
        try {
            skeleton = OBJECT_MAPPER.writeValueAsString(toThreadSkeleton(traces));
        } catch (JsonProcessingException ex) {
            throw new UncheckedIOException(ex);
        }
        String drillDownHint = "Call read(type=trace, id=<uuid>) on any trace id from the"
                + " thread skeleton above to inspect its full input/output + spans, or"
                + " jq(type=trace, id=<uuid>, expression='<path>') for path-targeted lookups.";
        String contextValue = "Thread skeleton (compact per-trace summary; use tools to drill in):\n"
                + skeleton + "\n\n" + drillDownHint;

        var renderedMessages = renderThreadMessagesWithReplacement(evaluatorCode.messages(),
                TraceThreadLlmAsJudgeCode.CONTEXT_VARIABLE_NAME, contextValue);
        return buildChatRequest(renderedMessages, evaluatorCode.schema(), structuredOutputStrategy);
    }

    /**
     * Compact per-trace summary the agentic-tools branch renders into the prompt
     * instead of the full trace list. ~100 chars per trace — so a 10K-trace thread
     * is ~1 MB, well under the model's window even without further compression. The
     * model picks ids from this list and drills in via ReadTool.
     */
    static List<ThreadTraceSkeleton> toThreadSkeleton(List<Trace> traces) {
        return traces.stream()
                .map(trace -> new ThreadTraceSkeleton(
                        trace.id(),
                        trace.name(),
                        trace.startTime(),
                        trace.endTime(),
                        trace.duration(),
                        trace.spanCount(),
                        trace.llmSpanCount()))
                .toList();
    }

    /**
     * Compact per-trace summary shipped to the model as part of the thread skeleton.
     * Field set is small on purpose — anything beyond this is a {@code read} away.
     *
     * <p>{@code @JsonNaming(SnakeCaseStrategy)} keeps the wire shape consistent with
     * {@link Trace}'s serialization (also snake_case via the same strategy), so the
     * model sees the same field names in the skeleton and in a follow-up
     * {@code read(type=trace, id=X)} response. Camel-case here would surprise the
     * model with two different schemas for the same entity.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @Builder(toBuilder = true)
    public record ThreadTraceSkeleton(
            @NonNull UUID id,
            String name,
            @NonNull Instant startTime,
            Instant endTime,
            Double duration,
            int spanCount,
            int llmSpanCount) {
    }

    /**
     * Light-weight twin of {@link #renderThreadMessages} that substitutes the
     * already-rendered {@code context} string directly, skipping the
     * Jackson-serialize-the-traces step. Used by the tools path where the variable
     * value (the skeleton + drill-down hint) is computed by the caller.
     *
     * <p>The caller (the thread scorer's {@code shouldUseAgenticTools} gate) detects
     * multimodal templates upstream via {@link #hasMultimodalTemplate(List)} and falls
     * back to the inline path, so by the time we get here {@code templateMessages} is
     * guaranteed string-only. The assertion below is a defensive net rather than the
     * primary safety mechanism — actual rendering delegates to
     * {@link #renderMessagesWithReplacements} so role-switch / template-engine logic
     * stays in one place.
     */
    private static List<ChatMessage> renderThreadMessagesWithReplacement(
            List<LlmAsJudgeMessage> templateMessages, String variableName, String contextValue) {
        if (hasMultimodalTemplate(templateMessages)) {
            throw new UnsupportedOperationException(
                    "Multimodal thread message content is not supported on the agentic-tools path");
        }
        Map<String, String> replacements = Map.of(variableName, contextValue);
        return renderMessagesWithReplacements(templateMessages, replacements);
    }

    static List<ChatMessage> renderThreadMessages(
            List<LlmAsJudgeMessage> templateMessages, Map<String, String> variablesMap, List<Trace> traces,
            List<Span> spans) {
        // prepare the map of replacements to use in all messages
        Map<String, String> replacements = variablesMap.keySet().stream()
                .map(variableName -> switch (variableName) {
                    case TraceThreadLlmAsJudgeCode.CONTEXT_VARIABLE_NAME -> {
                        // Always use the enriched shape — when `spans` is empty (toggle off),
                        // the `spans` field is omitted via @JsonInclude(NON_NULL) and the
                        // JSON is wire-identical to today's [{role, content}, ...] shape.
                        try {
                            yield MessageVariableMapping.builder()
                                    .variableName(variableName)
                                    .valueToReplace(OBJECT_MAPPER.writeValueAsString(
                                            fromTraceToThreadEnriched(traces, spans)))
                                    .build();
                        } catch (JsonProcessingException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    }
                    default -> throw new IllegalArgumentException("Invalid variable name: " + variableName);
                })
                .collect(
                        Collectors.toMap(MessageVariableMapping::variableName, MessageVariableMapping::valueToReplace));
        // Rendering itself is identical to trace / span flows once replacements are built —
        // delegate so role-fan-out, multimodal handling, and prompt-type defaults stay in
        // one place. The thread-specific bit is just the replacements assembly above.
        return renderMessagesWithReplacements(templateMessages, replacements);
    }

    /**
     * Render the rule evaluator message template using the values from an actual
     * trace.
     * <p>
     * As the rule may consist in multiple messages, we check each one of them for
     * variables to fill.
     * Then we go through every variable template to replace them for the value from
     * the trace.
     *
     * @param templateMessages a list of messages with variables to fill with a
     *                         Trace value
     * @param variablesMap     a map of template variable to a path to a value into
     *                         a Trace
     * @param trace            the trace with value to use to replace template
     *                         variables
     * @return a list of AI messages, with templates rendered
     */
    static List<ChatMessage> renderMessages(
            List<LlmAsJudgeMessage> templateMessages, Map<String, String> variablesMap, Trace trace) {
        Map<String, String> replacements = toReplacements(variablesMap, trace);
        return renderMessagesWithReplacements(templateMessages, replacements);
    }

    /**
     * Render the rule evaluator message template using the values from an actual span.
     * Similar to renderMessages but for spans.
     *
     * @param templateMessages a list of messages with variables to fill with a Span value
     * @param variablesMap     a map of template variable to a path to a value into a Span
     * @param span             the span with value to use to replace template variables
     * @return a list of AI messages, with templates rendered
     */
    static List<ChatMessage> renderMessages(
            List<LlmAsJudgeMessage> templateMessages, Map<String, String> variablesMap, Span span) {
        Map<String, String> replacements = toReplacements(variablesMap, span);
        return renderMessagesWithReplacements(templateMessages, replacements);
    }

    /**
     * Common implementation for rendering messages with replacements.
     * This method handles the actual message rendering logic that is shared between traces and spans.
     */
    private static List<ChatMessage> renderMessagesWithReplacements(
            List<LlmAsJudgeMessage> templateMessages, Map<String, String> replacements) {
        return renderMessagesWithReplacements(templateMessages, replacements, PromptType.MUSTACHE);
    }

    private static List<ChatMessage> renderMessagesWithReplacements(
            List<LlmAsJudgeMessage> templateMessages, Map<String, String> replacements, PromptType promptType) {
        // render the message templates from evaluator rule
        return templateMessages.stream()
                .map(templateMessage -> {
                    // Check if content is string (text) or array (multimodal)
                    if (templateMessage.isStringContent()) {
                        // String format: plain text content
                        var txtContent = templateMessage.asString();
                        var renderedMessage = TemplateParseUtils.render(txtContent, replacements, promptType);
                        return switch (templateMessage.role()) {
                            case USER -> UserMessage.from(renderedMessage);
                            case SYSTEM -> SystemMessage.from(renderedMessage);
                            default -> {
                                log.info("No mapping for message role type {}", templateMessage.role());
                                yield null;
                            }
                        };
                    } else if (templateMessage.isStructuredContent()) {
                        // Array format: structured content parts
                        return switch (templateMessage.role()) {
                            case USER -> buildUserMessageFromContentParts(
                                    templateMessage.asContentList(), replacements, promptType);
                            case SYSTEM -> {
                                // For SYSTEM messages with array content, extract first text part
                                var textContent = templateMessage.asContentList().stream()
                                        .filter(part -> "text".equals(part.type()))
                                        .map(LlmAsJudgeMessageContent::text)
                                        .filter(Objects::nonNull)
                                        .map(text -> TemplateParseUtils.render(text, replacements, promptType))
                                        .findFirst()
                                        .orElse("");
                                yield SystemMessage.from(textContent);
                            }
                            default -> {
                                log.info("No mapping for message role type {}", templateMessage.role());
                                yield null;
                            }
                        };
                    } else {
                        log.warn("Unknown content type for message role {}", templateMessage.role());
                        return null;
                    }
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Functional interface to extract JSON sections (input/output/metadata) from an entity.
     */
    @FunctionalInterface
    private interface JsonSectionExtractor {
        JsonNode extract(TraceSection section);
    }

    public static Map<String, String> toReplacements(Map<String, String> variables, Trace trace) {
        return toReplacements(variables, section -> switch (section) {
            case INPUT -> trace.input();
            case OUTPUT -> trace.output();
            case METADATA -> trace.metadata();
        });
    }

    /**
     * Variant that injects a built-in {@code spans} variable holding the trace's spans
     * projected to {@link SpanForLlm} and reconstructed as a parent→child tree. Used by
     * Python metrics whose {@code score(...)} signature accepts {@code spans}; the user
     * opts in by including a {@code "spans"} key in their {@code arguments} map. The value
     * the user maps for {@code spans} is overridden with the typed list — {@code spans} is
     * a reserved built-in, not a regular path-resolved variable.
     *
     * <p>The shape matches every other span-render path on this PR (trace-scope
     * {@code {{spans}}} for LLM-as-judge, thread-scope {@code {{context}}} for both LLM-as-
     * judge and Python): lean {@link SpanForLlm} projection (11 fields — name, type, in/out,
     * timing, model/provider, error_info, plus nested children), {@code @JsonInclude(NON_NULL)}
     * to keep the JSON tight, and call-order preserved by sorting siblings on {@code startTime}
     * at every level inside {@link #buildSpanTree}.
     *
     * <p>The returned map is typed as {@code Map<String, Object>} so the spans value can
     * carry the {@code List<SpanForLlm>} tree through Jackson serialization to the Python
     * runner as a JSON array. The Python side receives {@code spans} as a list of dicts
     * after {@code json.loads(...)} — not as a JSON string that the user would have to
     * re-parse.
     *
     * <p>Caller is responsible for only invoking this overload when the user actually
     * requested spans (i.e. {@code arguments.containsKey("spans")}). The scorer makes this
     * decision so the span fetch is skipped on metrics that don't need it.
     */
    public static Map<String, Object> toReplacements(
            @NonNull Map<String, String> variables, @NonNull Trace trace, @NonNull List<Span> spans) {
        var base = toReplacements(variables, trace);
        var result = new LinkedHashMap<String, Object>(base);
        result.put(SPANS_VARIABLE_NAME, buildSpanTree(spans));
        return result;
    }

    public static Map<String, String> toReplacements(Map<String, String> variables, Span span) {
        return toReplacements(variables, section -> switch (section) {
            case INPUT -> span.input();
            case OUTPUT -> span.output();
            case METADATA -> span.metadata();
        });
    }

    /**
     * Common implementation for converting variables to replacements.
     * Works for both Trace and Span by accepting a function to extract JSON sections.
     */
    private static Map<String, String> toReplacements(
            Map<String, String> variables, JsonSectionExtractor sectionExtractor) {
        var parsedVariables = toVariableMapping(variables);
        // extract the actual value from the entity
        return parsedVariables.stream().map(mapper -> {
            var section = mapper.traceSection();
            var jsonSection = section != null ? sectionExtractor.extract(section) : null;
            // if no section, there's no replacement and the literal value is taken
            var valueToReplace = jsonSection != null
                    ? extractFromJson(jsonSection, mapper.jsonPath())
                    : mapper.valueToReplace;
            return mapper.toBuilder()
                    .valueToReplace(valueToReplace)
                    .build();
        }).filter(mapper -> mapper.valueToReplace() != null)
                .collect(
                        Collectors.toMap(MessageVariableMapping::variableName, MessageVariableMapping::valueToReplace));
    }

    /**
     * Parse evaluator's variable mapper into a usable list of mappings.
     *
     * @param evaluatorVariables a map with variables and a path into a trace
     *                           input/output/metadata to replace
     * @return a parsed list of mappings, easier to use for the template rendering
     */
    static List<MessageVariableMapping> toVariableMapping(Map<String, String> evaluatorVariables) {
        return evaluatorVariables.entrySet().stream()
                .map(mapper -> {
                    var templateVariable = mapper.getKey();
                    var tracePath = mapper.getValue();
                    var builder = MessageVariableMapping.builder().variableName(templateVariable);
                    // check if its input/output/metadata variable and fix the json path
                    Arrays.stream(TraceSection.values())
                            .filter(traceSection -> {
                                // Match "input." or just "input" (same for output/metadata)
                                String prefixWithDot = traceSection.prefix;
                                String prefixWithoutDot = prefixWithDot.substring(0, prefixWithDot.length() - 1);
                                return tracePath.startsWith(prefixWithDot) || tracePath.equals(prefixWithoutDot);
                            })
                            .findFirst()
                            .ifPresentOrElse(traceSection -> {
                                // If path contains a dot, extract nested path; otherwise use root "$"
                                String jsonPath = tracePath.contains(".")
                                        ? "$." + tracePath.substring(traceSection.prefix.length())
                                        : "$";
                                builder.traceSection(traceSection).jsonPath(jsonPath);
                            },
                                    // if not a trace section, it's a literal value to replace
                                    () -> builder.valueToReplace(tracePath));

                    return builder.build();
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Build a UserMessage from structured content parts (array format).
     * Supports text, image_url, video_url, and audio_url content types.
     */
    private UserMessage buildUserMessageFromContentParts(
            List<LlmAsJudgeMessageContent> contentParts, Map<String, String> replacements) {
        return buildUserMessageFromContentParts(contentParts, replacements, PromptType.MUSTACHE);
    }

    private UserMessage buildUserMessageFromContentParts(
            List<LlmAsJudgeMessageContent> contentParts, Map<String, String> replacements, PromptType promptType) {
        var builder = UserMessage.builder();

        for (var part : contentParts) {
            switch (part.type()) {
                case "text" -> {
                    if (part.text() != null) {
                        var renderedText = TemplateParseUtils.render(part.text(), replacements, promptType);
                        if (StringUtils.isNotBlank(renderedText)) {
                            builder.addContent(TextContent.from(renderedText));
                        }
                    }
                }
                case "image_url" -> {
                    if (part.imageUrl() != null && part.imageUrl().url() != null) {
                        var url = TemplateParseUtils.render(part.imageUrl().url(), replacements, promptType);
                        var unescapedUrl = StringEscapeUtils.unescapeHtml4(url);
                        builder.addContent(ImageContent.from(unescapedUrl));
                    }
                }
                case "video_url" -> {
                    if (part.videoUrl() != null && part.videoUrl().url() != null) {
                        var url = TemplateParseUtils.render(part.videoUrl().url(), replacements, promptType);
                        var unescapedUrl = StringEscapeUtils.unescapeHtml4(url);
                        builder.addContent(VideoContent.from(unescapedUrl));
                    }
                }
                case "audio_url" -> {
                    if (part.audioUrl() != null && part.audioUrl().url() != null) {
                        var url = TemplateParseUtils.render(part.audioUrl().url(), replacements, promptType);
                        var unescapedUrl = StringEscapeUtils.unescapeHtml4(url);
                        builder.addContent(AudioContent.from(unescapedUrl));
                    }
                }
                default -> log.warn("Unknown content type: {}", part.type());
            }
        }

        return builder.build();
    }

    private static String extractFromJson(JsonNode json, String path) {
        // Special case: if path is "$", return the entire JSON object as string
        if ("$".equals(path)) {
            try {
                return OBJECT_MAPPER.writeValueAsString(json);
            } catch (JsonProcessingException e) {
                log.warn("failed to serialize entire json object, json={}", json, e);
                return null;
            }
        }

        Map<String, Object> forcedObject;
        try {
            // JsonPath didn't work with JsonNode, even explicitly using
            // JacksonJsonProvider, so we convert to a Map
            forcedObject = OBJECT_MAPPER.convertValue(json, new TypeReference<>() {
            });
        } catch (InvalidArgumentException e) {
            log.warn("failed to parse json, json={}", json, e);
            return null;
        }

        try {
            var value = JsonPath.parse(forcedObject).read(path);
            return value != null ? serializeToJsonString(value) : null;
        } catch (Exception e) {
            log.warn("couldn't find path inside json, trying flat structure, path={}, json={}", path, json, e);
            return Optional.ofNullable(forcedObject.get(path.replace("$.", "")))
                    .map(OnlineScoringEngine::serializeToJsonString)
                    .orElseGet(() -> {
                        log.info("couldn't find flat or nested path in json, path={}, json={}", path, json);
                        return null;
                    });
        }
    }

    /**
     * Serialize a value to a JSON string. For simple types (String, Number, Boolean),
     * returns the value directly as a string. For complex types (Map, List), serializes to JSON.
     */
    private static String serializeToJsonString(Object value) {
        if (value == null) {
            return null;
        }
        // For simple types, return as-is to preserve backward compatibility
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        // For complex types (Map, List, etc.), serialize to proper JSON
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize value to JSON, falling back to toString(), value={}", value, e);
            return value.toString();
        }
    }

    public static List<TraceThreadPythonEvaluatorRequest.ChatMessage> fromTraceToThread(List<Trace> traces) {
        return traces.stream()
                .flatMap(trace -> Stream.of(
                        TraceThreadPythonEvaluatorRequest.ChatMessage.builder()
                                .role(TraceThreadPythonEvaluatorRequest.ROLE_USER)
                                .content(trace.input())
                                .build(),
                        TraceThreadPythonEvaluatorRequest.ChatMessage.builder()
                                .role(TraceThreadPythonEvaluatorRequest.ROLE_ASSISTANT)
                                .content(trace.output())
                                .build()))
                .toList();
    }

    /**
     * Build the chat-message list that fills {@code {{context}}} on the LLM thread render
     * path, optionally enriched with each trace's child spans attached to its assistant
     * message. Backward-compatible: the {@code spans} field is omitted from the JSON whenever
     * a trace has no spans (or the caller passed an empty list), so a rule written against
     * today's {@code [{role, content}, ...]} shape sees no difference.
     *
     * <p>Spans within each trace are sorted by start_time so the wire order matches call
     * order — same convention as the trace-scope {@code {{spans}}} path.
     *
     * <p>Returns the same {@link TraceThreadPythonEvaluatorRequest.ChatMessage} type as
     * {@link #fromTraceToThread(List)} so both render paths share one wire shape: the
     * unified {@code ChatMessage} carries an optional {@code spans} field (omitted via
     * {@code @JsonInclude(NON_NULL)} when null), making the enriched JSON a strict
     * superset of the legacy {@code [{role, content}, ...]} contract.
     */
    public static List<TraceThreadPythonEvaluatorRequest.ChatMessage> fromTraceToThreadEnriched(
            @NonNull List<Trace> traces, @NonNull List<Span> spans) {
        Map<UUID, List<Span>> spansByTrace = spans.stream()
                .collect(Collectors.groupingBy(Span::traceId));
        return traces.stream()
                .flatMap(trace -> {
                    // Reconstruct parent → child hierarchy per-trace so the assistant entry
                    // carries a tree of spans, not a flat list. buildSpanTree handles sorting
                    // siblings by start_time at every level.
                    List<SpanForLlm> traceSpans = buildSpanTree(
                            spansByTrace.getOrDefault(trace.id(), List.of()));
                    return Stream.of(
                            TraceThreadPythonEvaluatorRequest.ChatMessage.builder()
                                    .role(TraceThreadPythonEvaluatorRequest.ROLE_USER)
                                    .content(trace.input())
                                    .build(),
                            TraceThreadPythonEvaluatorRequest.ChatMessage.builder()
                                    .role(TraceThreadPythonEvaluatorRequest.ROLE_ASSISTANT)
                                    .content(trace.output())
                                    .spans(traceSpans.isEmpty() ? null : traceSpans)
                                    .build());
                })
                .toList();
    }

    /**
     * Build a nested-tree projection of the given spans for inline LLM rendering.
     *
     * <p>Reconstructs parent → child hierarchy from {@code parentSpanId} links, projects each
     * node to {@link SpanForLlm} (dropping the audit/score/cost noise that {@code Span}
     * carries), and sorts siblings at every level by {@code startTime} so the wire order
     * tracks call order within each branch. Returns the top-level roots.
     *
     * <p>Orphans (spans whose {@code parentSpanId} isn't in the input list) are promoted to
     * roots — happens when the caller passes a subset of a trace's spans, or when a parent
     * was dropped server-side. The tree stays well-formed instead of silently dropping the
     * orphaned subtree.
     */
    public static List<SpanForLlm> buildSpanTree(@NonNull List<Span> spans) {
        if (spans.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<Span>> childrenByParent = new HashMap<>();
        Set<UUID> presentIds = new HashSet<>();
        for (Span span : spans) {
            if (span.id() != null) {
                presentIds.add(span.id());
            }
            UUID parentId = span.parentSpanId();
            if (parentId != null) {
                childrenByParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(span);
            }
        }
        // Roots: parent is null OR parent isn't in the visible set (orphan promotion).
        List<Span> roots = spans.stream()
                .filter(s -> s.parentSpanId() == null || !presentIds.contains(s.parentSpanId()))
                .sorted(BY_SPAN_START_TIME)
                .toList();
        return roots.stream()
                .map(root -> buildSpanNode(root, childrenByParent))
                .toList();
    }

    private static SpanForLlm buildSpanNode(Span span, Map<UUID, List<Span>> childrenByParent) {
        List<SpanForLlm> children = span.id() == null
                ? List.of()
                : childrenByParent.getOrDefault(span.id(), List.of()).stream()
                        .sorted(BY_SPAN_START_TIME)
                        .map(child -> buildSpanNode(child, childrenByParent))
                        .toList();
        return SpanForLlm.builder()
                .name(span.name())
                .type(span.type())
                .startTime(span.startTime())
                .endTime(span.endTime())
                .duration(span.duration())
                .input(span.input())
                .output(span.output())
                .metadata(span.metadata())
                .model(span.model())
                .provider(span.provider())
                .errorInfo(span.errorInfo())
                .spans(children.isEmpty() ? null : children)
                .build();
    }

    public record ParsedFeedbackScores(List<FeedbackScoreBatchItem> scores, List<String> nullScoreNames) {
        public static ParsedFeedbackScores empty() {
            return new ParsedFeedbackScores(List.of(), List.of());
        }
    }

    public static void logSkippedNullScores(
            Logger userFacingLogger, ParsedFeedbackScores parsed, String entityType, Object entityId) {
        parsed.nullScoreNames().forEach(name -> userFacingLogger.info(
                "Skipped score '{}' for {} '{}' because the judge returned a null value (treated as not applicable)",
                name, entityType, entityId));
    }

    public static ParsedFeedbackScores toFeedbackScores(@NonNull ChatResponse chatResponse) {
        var content = extractJson(chatResponse.aiMessage().text());
        JsonNode structuredResponse;
        try {
            structuredResponse = OBJECT_MAPPER.readTree(content);
            if (!structuredResponse.isObject()) {
                log.info("ChatResponse content returned into an empty JSON result");
                return ParsedFeedbackScores.empty();
            }
        } catch (JsonProcessingException e) {
            log.error("parsing LLM response into a JSON: {}", content, e);
            return ParsedFeedbackScores.empty();
        }
        List<FeedbackScoreBatchItem> results = new ArrayList<>();
        List<String> nullScoreNames = new ArrayList<>();
        structuredResponse.properties().forEach(scoreMetric -> {
            var scoreName = scoreMetric.getKey();
            var scoreNested = scoreMetric.getValue();
            if (scoreNested == null || scoreNested.isMissingNode() || !scoreNested.has(SCORE_FIELD_NAME)) {
                log.debug("No score found for '{}' score in {}", scoreName, scoreNested);
                return;
            }
            var actualScore = scoreNested.path(SCORE_FIELD_NAME);
            if (actualScore.isNull()) {
                log.debug("Skipping '{}' score because the judge returned a null value", scoreName);
                nullScoreNames.add(scoreName);
                return;
            }
            var resultBuilder = FeedbackScoreBatchItem.builder()
                    .name(scoreName)
                    .reason(scoreNested.path(REASON_FIELD_NAME).asText())
                    .source(ScoreSource.ONLINE_SCORING);
            if (actualScore.isBoolean()) {
                resultBuilder.value(actualScore.asBoolean() ? BigDecimal.ONE : BigDecimal.ZERO);
            } else {
                resultBuilder.value(actualScore.decimalValue());
            }
            results.add(resultBuilder.build());
        });
        if (results.isEmpty() && nullScoreNames.isEmpty()) {
            var topLevelKeys = StreamSupport.stream(
                    Spliterators.spliteratorUnknownSize(structuredResponse.fieldNames(),
                            Spliterator.ORDERED | Spliterator.NONNULL),
                    false)
                    .toList();
            var truncated = content.length() > 500 ? content.substring(0, 500) + "..." : content;
            log.warn(
                    "Invalid LLM output format for feedback scores. Expected structure: { '<scoreName>': { 'score': <number|boolean>, 'reason': <string> } }. Top-level keys: '{}'. Raw response (truncated): '{}'",
                    topLevelKeys, truncated);
        }
        return new ParsedFeedbackScores(results, nullScoreNames);
    }

    private static String extractJson(String response) {
        Matcher matcher = JSON_BLOCK_PATTERN.matcher(response);
        if (matcher.find()) {
            return matcher.group(1);
        }

        // Assume the whole response is raw JSON
        return response.trim();
    }

    @AllArgsConstructor
    enum TraceSection {
        INPUT("input."),
        OUTPUT("output."),
        METADATA("metadata.");

        final String prefix;
    }

    @Builder(toBuilder = true)
    record MessageVariableMapping(
            TraceSection traceSection, String variableName, String jsonPath, String valueToReplace) {
    }

    /**
     * Shared "evaluate → prepare → log" wrapper used by the trace and span Python scorers.
     * Eliminates the boilerplate that duplicated the MDC scope, the "Evaluating X 'id' sampled
     * by rule 'name'" entry log, the "Sending X 'id' to Python evaluator: '<summary>'" exit
     * log, and the rethrow-with-error-log fallback. Callers supply only what actually differs:
     * the entity label ({@code "traceId"} / {@code "spanId"}), the id, the rule name, and a
     * supplier that builds the rendered evaluator input.
     *
     * <p>Error-path logging is split: {@code userFacingLogger} gets a sanitized one-liner
     * (no Throwable, so internal class names / paths from the stack trace don't leak into the
     * user-facing log sink), and {@code internalLogger} (the scorer's slf4j logger) gets the
     * full stack trace.
     */
    public static Map<String, Object> logAndPrepareEvaluatorInput(
            @NonNull Logger userFacingLogger,
            @NonNull Logger internalLogger,
            @NonNull Map<String, String> mdc,
            @NonNull String entityLabel,
            @NonNull Object entityId,
            String ruleName,
            @NonNull Supplier<Map<String, Object>> dataSupplier) {
        try (var logContext = LogContextAware.wrapWithMdc(mdc)) {
            userFacingLogger.info("Evaluating {} '{}' sampled by rule '{}'", entityLabel, entityId, ruleName);
            try {
                Map<String, Object> data = dataSupplier.get();
                if (userFacingLogger.isInfoEnabled()) {
                    userFacingLogger.info("Sending {} '{}' to Python evaluator: '{}'",
                            entityLabel, entityId, summarizeEvaluatorInput(data));
                }
                return data;
            } catch (Exception exception) {
                userFacingLogger.error("Error preparing Python request for {} '{}'", entityLabel, entityId);
                internalLogger.error("Error preparing Python request for {} '{}'",
                        entityLabel, entityId, exception);
                throw exception;
            }
        }
    }

    /**
     * Shape-only summary of the rendered Python evaluator input for user-facing logs.
     * Values are rendered trace/span content (input/output/metadata/spans); logging them
     * verbatim would land user data downstream of whatever sinks the user-facing log feeds,
     * so we surface key names and sizes only.
     */
    public static String summarizeEvaluatorInput(@NonNull Map<String, Object> data) {
        var parts = data.entrySet().stream()
                .map(e -> {
                    var v = e.getValue();
                    if (v instanceof List<?> list) {
                        return String.format("%s=list(%d)", e.getKey(), list.size());
                    }
                    var s = v == null ? "" : v.toString();
                    return String.format("%s=%dc", e.getKey(), s.length());
                })
                .collect(Collectors.joining(", "));
        return String.format("arguments=[%s]", parts);
    }

    /**
     * Shared error-logging helper for the {@code prepareEvaluation} catch blocks on the trace
     * and thread scorers. The two loggers are intentional:
     * <ul>
     *   <li>{@code userFacingLogger} carries a sanitized one-liner with the entity id only —
     *       no Throwable, so the stack trace (with internal class names / paths) doesn't leak
     *       into the user-facing log sink.</li>
     *   <li>{@code internalLogger} (the scorer's slf4j logger) carries the full stack trace
     *       so an operator can diagnose what actually broke.</li>
     * </ul>
     * <p>The {@code idLabel} parameter ({@code "traceId"} / {@code "threadId"}) and {@code id}
     * are formatted with single-quoted placeholders per the backend logging convention.
     */
    public static void logPreparingLlmRequestError(@NonNull Logger userFacingLogger,
            @NonNull Logger internalLogger, @NonNull String idLabel, @NonNull Object id,
            @NonNull Exception exception) {
        userFacingLogger.error("Error preparing LLM request for {} '{}'", idLabel, id);
        internalLogger.error("Error preparing LLM request for {} '{}'", idLabel, id, exception);
    }

    /**
     * Whether any of the template messages declares non-string (multimodal) content. The
     * agentic-tools render path on threads only substitutes the context variable into string
     * content — multimodal templates (image / audio / video alongside text) are rejected.
     * Callers detect this here and fall back to the inline path rather than throwing.
     */
    public static boolean hasMultimodalTemplate(@NonNull List<LlmAsJudgeMessage> templateMessages) {
        return templateMessages.stream().anyMatch(m -> !m.isStringContent());
    }

}
