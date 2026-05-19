package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.PromptType;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.evaluators.LlmAsJudgeMessageContent;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.resources.v1.events.tools.StringTruncator;
import com.comet.opik.api.resources.v1.events.tools.ToolRegistry;
import com.comet.opik.api.resources.v1.events.tools.TraceCompressor;
import com.comet.opik.domain.evaluators.python.TraceThreadPythonEvaluatorRequest;
import com.comet.opik.domain.llm.structuredoutput.StructuredOutputStrategy;
import com.comet.opik.infrastructure.log.LogContextAware;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TemplateParseUtils;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import com.google.api.gax.rpc.InvalidArgumentException;
import com.google.common.base.Preconditions;
import com.jayway.jsonpath.JsonPath;
import dev.langchain4j.data.message.AudioContent;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
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
        Map<String, String> replacements = toReplacements(evaluatorCode.variables(), trace);
        injectSpansIntoReplacements(replacements, evaluatorCode.variables(),
                evaluatorCode.messages(), promptType, spans);
        var renderedMessages = renderMessagesWithReplacements(evaluatorCode.messages(), replacements, promptType);
        return buildChatRequest(renderedMessages, evaluatorCode.schema(), structuredOutputStrategy);
    }

    /**
     * Variant of {@link #prepareLlmRequest(LlmAsJudgeCode, Trace, StructuredOutputStrategy, PromptType, List)}
     * that caps each rendered variable substitution at {@code maxReplacementChars}. Values longer
     * than the cap are replaced with their first {@code maxReplacementChars} chars followed by
     * {@code drillDownHint}. Used by the test-suite-assertion (tool-enabled) path, so a 50K-token
     * trace's input/output doesn't get pasted verbatim into the prompt — the agent can pull the
     * full content via the {@code read} tool when it actually needs it.
     */
    public static ChatRequest prepareLlmRequest(
            @NonNull LlmAsJudgeCode evaluatorCode, Trace trace,
            StructuredOutputStrategy structuredOutputStrategy, @NonNull PromptType promptType,
            int maxReplacementChars, @NonNull String drillDownHint, @NonNull List<Span> spans) {
        Map<String, String> replacements = toReplacements(evaluatorCode.variables(), trace);
        injectSpansIntoReplacements(replacements, evaluatorCode.variables(),
                evaluatorCode.messages(), promptType, spans);
        Map<String, String> capped = capReplacements(replacements, maxReplacementChars, drillDownHint);
        var renderedMessages = renderMessagesWithReplacements(evaluatorCode.messages(), capped, promptType);
        return buildChatRequest(renderedMessages, evaluatorCode.schema(), structuredOutputStrategy);
    }

    /**
     * Whether the rule needs the trace's spans list rendered into the prompt. Two ways to
     * opt in:
     * <ul>
     *   <li>Sentinel-valued variable: any entry in {@code variables} whose value is the bare
     *       string {@code "spans"} (no JSONPath prefix) — mirrors the Python-metric convention
     *       and is what the FE writes when the user types {@code {{spans}}} in the prompt.
     *   <li>Direct template reference: any message in {@code messages} references
     *       {@code {{spans}}} (per {@code promptType}) without the variables map mapping it
     *       to a custom path. Catches API-created rules where the caller put {@code {{spans}}}
     *       in the prompt but didn't (or didn't know to) set the sentinel mapping.
     * </ul>
     *
     * <p>Used by the trace scorer to opt-in to the {@code spanService.getByTraceIds(...)} fetch
     * for inline LLM-as-judge evaluations whose template references {@code {{spans}}}.
     */
    public static boolean templateReferencesSpans(
            @NonNull List<LlmAsJudgeMessage> messages,
            @NonNull Map<String, String> variables,
            @NonNull PromptType promptType) {
        return variables.containsValue(SPANS_VARIABLE_NAME)
                || messagesReferenceSpansDirectly(messages, variables, promptType);
    }

    /**
     * True when at least one message template references {@code {{spans}}} (or the equivalent
     * for {@code promptType}) AND the variables map does not bind {@code spans} to a custom
     * path. The second clause respects explicit user mappings — e.g. a rule that maps
     * {@code spans} to {@code input.something} keeps that mapping instead of being silently
     * overridden by the spans-list injection.
     *
     * <p>Walks both message shapes: the simple-string {@code content} field, and the
     * multimodal {@code contentArray} where each part exposes its own {@code text}. Scanning
     * only {@code content} would miss {@code {{spans}}} in multimodal prompts, leaving the
     * rendered text part unsubstituted because the spans fetch never fires.
     */
    private static boolean messagesReferenceSpansDirectly(
            List<LlmAsJudgeMessage> messages, Map<String, String> variables, PromptType promptType) {
        if (variables.containsKey(SPANS_VARIABLE_NAME)) {
            return false;
        }
        return messages.stream()
                .filter(Objects::nonNull)
                .flatMap(OnlineScoringEngine::renderableTextOf)
                .anyMatch(text -> TemplateParseUtils.extractVariables(text, promptType)
                        .contains(SPANS_VARIABLE_NAME));
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
     * Replace any variable whose source path is the {@code "spans"} sentinel with the JSON-
     * serialized spans list (sorted by start_time, matching the Python-metric convention).
     * Mutates {@code replacements} in place. No-op when no variable references the sentinel
     * and no message template references {@code {{spans}}} directly.
     *
     * <p>An empty spans list still triggers the rewrite (rendering as {@code "[]"}) — without
     * it, {@code toReplacements} leaves the bare {@code "spans"} value as a literal and the
     * prompt renders the word "spans" instead of an empty array. <strong>Intentionally not
     * gated by {@code isAgenticToolsEnabled}</strong>: when the toggle is off, the scorer
     * skips the spans fetch and threads an empty list here, which still rewrites
     * sentinel-mapped variables to {@code "[]"}. Gating this would resurrect the bare-word
     * leak for rules whose variables map still carries the sentinel from before the toggle
     * flipped. See {@code OnlineScoringLlmAsJudgeScorer.shouldFetchSpans} for the full
     * toggle-semantics rationale.
     *
     * <p>Also handles the implicit-reference case (template uses {@code {{spans}}} but the
     * variables map doesn't bind it): mirrors the FE auto-fill server-side so API-created
     * rules get the same behavior without forcing every caller to know the sentinel convention.
     */
    private static void injectSpansIntoReplacements(
            Map<String, String> replacements, Map<String, String> variables,
            List<LlmAsJudgeMessage> messages, PromptType promptType, List<Span> spans) {
        boolean sentinelMapped = variables.containsValue(SPANS_VARIABLE_NAME);
        boolean templateOnly = messagesReferenceSpansDirectly(messages, variables, promptType);
        if (!sentinelMapped && !templateOnly) {
            return;
        }
        String spansJson;
        try {
            spansJson = OBJECT_MAPPER.writeValueAsString(spans.stream().sorted(BY_SPAN_START_TIME).toList());
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
        variables.forEach((name, path) -> {
            if (SPANS_VARIABLE_NAME.equals(path)) {
                replacements.put(name, spansJson);
            }
        });
        if (templateOnly) {
            replacements.put(SPANS_VARIABLE_NAME, spansJson);
        }
    }

    static Map<String, String> capReplacements(Map<String, String> replacements,
            int maxReplacementChars, String drillDownHint) {
        return replacements.entrySet().stream().collect(Collectors.toMap(
                Map.Entry::getKey,
                e -> StringTruncator.truncate(e.getValue(), maxReplacementChars, drillDownHint),
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
     * (sorted by start_time) as a real JSON array. Used by Python metrics whose
     * {@code score(...)} signature accepts {@code spans}; the user opts in by including a
     * {@code "spans"} key in their {@code arguments} map. The value the user maps for
     * {@code spans} is overridden with the typed list — {@code spans} is a reserved
     * built-in, not a regular path-resolved variable.
     *
     * <p>The returned map is typed as {@code Map<String, Object>} so the spans value can
     * carry a {@code List<Span>} through Jackson serialization to the Python runner as a
     * JSON array. The Python side receives {@code spans} as a list of dicts after
     * {@code json.loads(...)} — not as a JSON string that the user would have to re-parse.
     *
     * <p>Caller is responsible for only invoking this overload when the user actually
     * requested spans (i.e. {@code arguments.containsKey("spans")}). The scorer makes this
     * decision so the span fetch is skipped on metrics that don't need it.
     */
    public static Map<String, Object> toReplacements(
            @NonNull Map<String, String> variables, @NonNull Trace trace, @NonNull List<Span> spans) {
        var base = toReplacements(variables, trace);
        var result = new LinkedHashMap<String, Object>(base);
        result.put(SPANS_VARIABLE_NAME, spans.stream().sorted(BY_SPAN_START_TIME).toList());
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
     * <p>Separate from {@link #fromTraceToThread(List)} which keeps returning the bare
     * Python-evaluator {@code ChatMessage} shape — the Python runner has its own contract
     * and we don't want to leak the {@code spans} field into that path.
     */
    public static List<EnrichedThreadChatMessage> fromTraceToThreadEnriched(
            @NonNull List<Trace> traces, @NonNull List<Span> spans) {
        Map<UUID, List<Span>> spansByTrace = spans.stream()
                .collect(Collectors.groupingBy(Span::traceId));
        return traces.stream()
                .flatMap(trace -> {
                    List<Span> traceSpans = spansByTrace.getOrDefault(trace.id(), List.of()).stream()
                            .sorted(BY_SPAN_START_TIME)
                            .toList();
                    return Stream.of(
                            EnrichedThreadChatMessage.builder()
                                    .role(TraceThreadPythonEvaluatorRequest.ROLE_USER)
                                    .content(trace.input())
                                    .build(),
                            EnrichedThreadChatMessage.builder()
                                    .role(TraceThreadPythonEvaluatorRequest.ROLE_ASSISTANT)
                                    .content(trace.output())
                                    .spans(traceSpans.isEmpty() ? null : traceSpans)
                                    .build());
                })
                .toList();
    }

    /**
     * Enriched chat-message shape for thread-scope {@code {{context}}} rendering. Extends
     * the existing {@code {role, content}} contract with an optional {@code spans} field
     * carrying the assistant turn's tool calls and other child spans. The field is omitted
     * from the JSON when null (via {@link JsonInclude.Include#NON_NULL}) so existing rules
     * that don't care about spans see the exact same wire shape they see today.
     */
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    @Builder(toBuilder = true)
    public record EnrichedThreadChatMessage(String role, JsonNode content, List<Span> spans) {
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
     * Rough character-based token estimate for the {@code {trace, spans}} composite. Used by
     * {@code OnlineScoringLlmAsJudgeScorer} to decide whether a trace is big enough that the
     * inline-rendered prompt would risk overflowing the model's window — which flips the
     * scorer into the read/jq/search agentic-tools path.
     *
     * <p>{@code charsPerToken} is the chars-per-token ratio operators configure via
     * {@code onlineScoring.agenticToolsCharsPerToken} (default 4 = natural-language English).
     * Workloads that skew toward code/JSON should lower the ratio (~ 2) to pull the size-based
     * branch in earlier. Accuracy isn't precision-critical because the threshold itself has
     * slack (default 50K tokens vs typical 128K windows), and the per-call and cumulative
     * output caps in {@link com.comet.opik.api.resources.v1.events.tools.ReadTool} pick up
     * any slack on the agentic-tools side.
     */
    public static int estimateTraceContextTokens(@NonNull Trace trace, @NonNull List<Span> spans,
            @NonNull TraceCompressor traceCompressor, int charsPerToken) {
        return estimateTokensFromJson(traceCompressor.buildFullJson(trace, spans), charsPerToken);
    }

    /**
     * Same as {@link #estimateTraceContextTokens} but skips the JSON build. Used when the
     * caller already has the full {@code {trace, spans}} JSON in hand (e.g. when it's going
     * to be pre-seeded into the tool context's cache anyway) — avoids serializing the trace
     * twice on big-trace evaluations where every redundant {@code buildFullJson} burns CPU
     * and GC churn.
     */
    public static int estimateTokensFromJson(@NonNull JsonNode fullJson, int charsPerToken) {
        Preconditions.checkArgument(charsPerToken >= 1, "charsPerToken must be >= 1, got %s", charsPerToken);
        return fullJson.toString().length() / charsPerToken;
    }

    /**
     * Rough character-based token estimate for the thread context as it would be
     * rendered on the inline path. Estimates the enriched shape — trace input/output
     * plus the assistant turn's child spans (tool calls + I/O) — so the agentic-tools
     * routing decision reflects what {@link #prepareThreadLlmRequest} will actually
     * serialize. Pass an empty {@code spans} list when the toggle is off; the
     * enriched serializer omits the {@code spans} field via {@code @JsonInclude(NON_NULL)},
     * so the estimate then matches the original trace-bodies-only shape exactly.
     *
     * <p>Used by {@code OnlineScoringTraceThreadLlmAsJudgeScorer} to decide whether
     * a thread is big enough to switch to the agentic-tools path (skeleton +
     * drill-down via ReadTool).
     *
     * <p>Same {@code charsPerToken} contract as {@link #estimateTraceContextTokens}:
     * configurable via {@code onlineScoring.agenticToolsCharsPerToken}.
     */
    public static int estimateThreadContextTokens(
            @NonNull List<Trace> traces, @NonNull List<Span> spans, int charsPerToken) {
        Preconditions.checkArgument(charsPerToken >= 1, "charsPerToken must be >= 1, got %s", charsPerToken);
        try {
            return OBJECT_MAPPER.writeValueAsString(fromTraceToThreadEnriched(traces, spans)).length()
                    / charsPerToken;
        } catch (JsonProcessingException ex) {
            throw new UncheckedIOException(ex);
        }
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
     * Whether the given provider is known to support tool-calling. Used to gate the
     * agentic-tools path: providers that don't support tools fall back to the inline path
     * even when the context exceeds the size threshold (which may overflow the model's
     * window — in that case the operator should pick a different model for those workloads).
     */
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

    public static boolean supportsToolCalling(@NonNull LlmProvider provider) {
        return switch (provider) {
            case OPEN_AI, ANTHROPIC, GEMINI, OPEN_ROUTER, VERTEX_AI, BEDROCK -> true;
            case OLLAMA, CUSTOM_LLM, OPIK_FREE -> false;
        };
    }

    /**
     * Build a sanitized one-line description of the outgoing LLM request for user-facing logs.
     * The full {@link ChatRequest} contains the rendered prompt, the user message with the
     * trace's input/output, request parameters, and tool specs — surfacing all of it in a
     * stored log lands trace content (and any tokens or PII it carries) in clear text
     * downstream of whatever sinks the log feeds. Shape-only summary instead.
     */
    public static String summarizeRequest(@NonNull ChatRequest request, @NonNull String modelName,
            boolean useTools) {
        // Intentionally NOT computing total chars: m.toString() on a multi-MB rendered prompt
        // allocates the full string just to measure its length, which would add ~2x prompt-size
        // heap churn per evaluation. Message count + tool count are enough to identify what's
        // happening; an operator who needs byte-level detail can hit the rule's debug log.
        int messageCount = request.messages() == null ? 0 : request.messages().size();
        int toolSpecCount = request.toolSpecifications() == null ? 0 : request.toolSpecifications().size();
        return String.format("model='%s', messages=%d, tools=%d, toolsEnabled=%s",
                modelName, messageCount, toolSpecCount, useTools);
    }

    /**
     * Build a sanitized one-line description of the LLM response. The full {@link ChatResponse}
     * carries the assistant text and any tool-call arguments, both of which can echo trace
     * content the model is reasoning about — surfacing the raw response in a user-facing log
     * lands trace content (and any tokens or PII it carries) downstream of whatever sinks the
     * log feeds. Shape-only summary instead.
     */
    public static String summarizeResponse(@NonNull ChatResponse response) {
        var ai = response.aiMessage();
        int textLength = ai.text() == null ? 0 : ai.text().length();
        int toolCallCount = ai.toolExecutionRequests() == null ? 0 : ai.toolExecutionRequests().size();
        var finishReason = response.metadata() == null ? null : response.metadata().finishReason();
        return String.format("textChars=%d, toolCalls=%d, finishReason=%s",
                textLength, toolCallCount, finishReason);
    }

    /**
     * Attach the tool specs from {@code toolRegistry} and the given {@code toolChoice} to
     * {@code request}'s parameters. Tool specs live inside {@link ChatRequestParameters}, so
     * we copy the existing parameters via {@code overrideWith} and layer tool specs on top —
     * setting {@code toolSpecifications} directly on the {@link ChatRequest} builder would
     * conflict with parameters. {@code toBuilder()} (rather than a fresh builder + .messages())
     * preserves any other top-level fields on ChatRequest, present or future, guarding against
     * a "silently dropped fields" regression between the initial scoring call and the
     * structured re-issue in the tool-call wrap-up.
     */
    public static ChatRequest addToolSpecs(@NonNull ChatRequest request, @NonNull ToolChoice toolChoice,
            @NonNull ToolRegistry toolRegistry) {
        var parameters = ChatRequestParameters.builder()
                .overrideWith(request.parameters())
                .toolSpecifications(toolRegistry.specs())
                .toolChoice(toolChoice)
                .build();
        return request.toBuilder()
                .parameters(parameters)
                .build();
    }
}
