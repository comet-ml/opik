package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.PromptType;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.evaluators.LlmAsJudgeMessageContent;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.domain.evaluators.python.TraceThreadPythonEvaluatorRequest;
import com.comet.opik.domain.llm.structuredoutput.StructuredOutputStrategy;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TemplateParseUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.gax.rpc.InvalidArgumentException;
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
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.text.StringEscapeUtils;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Spliterator;
import java.util.Spliterators;
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

    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.getMapper();

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile(
            "```(?:json)?\\s*(\\{.*?})\\s*```", Pattern.DOTALL);

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
            @NotNull LlmAsJudgeCode evaluatorCode, Trace trace, StructuredOutputStrategy structuredOutputStrategy) {
        var renderedMessages = renderMessages(evaluatorCode.messages(), evaluatorCode.variables(), trace);
        return buildChatRequest(renderedMessages, evaluatorCode.schema(), structuredOutputStrategy);
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
            @NotNull AutomationRuleEvaluatorSpanLlmAsJudge.SpanLlmAsJudgeCode evaluatorCode,
            @NotNull Span span,
            @NotNull StructuredOutputStrategy structuredOutputStrategy) {
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
            @NotNull TraceThreadLlmAsJudgeCode evaluatorCode, @NotNull List<Trace> traces,
            @NotNull StructuredOutputStrategy structuredOutputStrategy) {
        var renderedMessages = renderThreadMessages(evaluatorCode.messages(),
                Map.of(TraceThreadLlmAsJudgeCode.CONTEXT_VARIABLE_NAME, ""), traces);
        return buildChatRequest(renderedMessages, evaluatorCode.schema(), structuredOutputStrategy);
    }

    static List<ChatMessage> renderThreadMessages(
            List<LlmAsJudgeMessage> templateMessages, Map<String, String> variablesMap, List<Trace> traces) {
        // prepare the map of replacements to use in all messages
        Map<String, String> replacements = variablesMap.keySet().stream()
                .map(variableName -> switch (variableName) {
                    case TraceThreadLlmAsJudgeCode.CONTEXT_VARIABLE_NAME -> {
                        try {
                            yield MessageVariableMapping.builder()
                                    .variableName(variableName)
                                    .valueToReplace(OBJECT_MAPPER.writeValueAsString(fromTraceToThread(traces)))
                                    .build();
                        } catch (JsonProcessingException ex) {
                            throw new UncheckedIOException(ex);
                        }
                    }
                    default -> throw new IllegalArgumentException("Invalid variable name: " + variableName);
                })
                .collect(
                        Collectors.toMap(MessageVariableMapping::variableName, MessageVariableMapping::valueToReplace));

        // render the message templates from evaluator rule
        return templateMessages.stream()
                .map(templateMessage -> {
                    // Check if content is string (text) or array (multimodal)
                    if (templateMessage.isStringContent()) {
                        // String format: plain text content
                        var renderedMessage = TemplateParseUtils.render(
                                templateMessage.asString(), replacements, PromptType.MUSTACHE);
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
                                    templateMessage.asContentList(), replacements);
                            case SYSTEM -> {
                                // For SYSTEM messages with array content, extract first text part
                                var textContent = templateMessage.asContentList().stream()
                                        .filter(part -> "text".equals(part.type()))
                                        .map(LlmAsJudgeMessageContent::text)
                                        .filter(Objects::nonNull)
                                        .map(text -> TemplateParseUtils.render(text, replacements, PromptType.MUSTACHE))
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
        // render the message templates from evaluator rule
        return templateMessages.stream()
                .map(templateMessage -> {
                    // Check if content is string (text) or array (multimodal)
                    if (templateMessage.isStringContent()) {
                        // String format: plain text content
                        var txtContent = templateMessage.asString();
                        var renderedMessage = TemplateParseUtils.render(txtContent, replacements, PromptType.MUSTACHE);
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
                                    templateMessage.asContentList(), replacements);
                            case SYSTEM -> {
                                // For SYSTEM messages with array content, extract first text part
                                var textContent = templateMessage.asContentList().stream()
                                        .filter(part -> "text".equals(part.type()))
                                        .map(LlmAsJudgeMessageContent::text)
                                        .filter(Objects::nonNull)
                                        .map(text -> TemplateParseUtils.render(text, replacements, PromptType.MUSTACHE))
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
        var builder = UserMessage.builder();

        for (var part : contentParts) {
            switch (part.type()) {
                case "text" -> {
                    if (part.text() != null) {
                        var renderedText = TemplateParseUtils.render(part.text(), replacements, PromptType.MUSTACHE);
                        if (StringUtils.isNotBlank(renderedText)) {
                            builder.addContent(TextContent.from(renderedText));
                        }
                    }
                }
                case "image_url" -> {
                    if (part.imageUrl() != null && part.imageUrl().url() != null) {
                        var url = TemplateParseUtils.render(part.imageUrl().url(), replacements, PromptType.MUSTACHE);
                        var unescapedUrl = StringEscapeUtils.unescapeHtml4(url);
                        builder.addContent(ImageContent.from(unescapedUrl));
                    }
                }
                case "video_url" -> {
                    if (part.videoUrl() != null && part.videoUrl().url() != null) {
                        var url = TemplateParseUtils.render(part.videoUrl().url(), replacements, PromptType.MUSTACHE);
                        var unescapedUrl = StringEscapeUtils.unescapeHtml4(url);
                        builder.addContent(VideoContent.from(unescapedUrl));
                    }
                }
                case "audio_url" -> {
                    if (part.audioUrl() != null && part.audioUrl().url() != null) {
                        var url = TemplateParseUtils.render(part.audioUrl().url(), replacements, PromptType.MUSTACHE);
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

    public static List<FeedbackScoreBatchItem> toFeedbackScores(@NotNull ChatResponse chatResponse) {
        var content = extractJson(chatResponse.aiMessage().text());
        JsonNode structuredResponse;
        try {
            structuredResponse = OBJECT_MAPPER.readTree(content);
            if (!structuredResponse.isObject()) {
                log.info("ChatResponse content returned into an empty JSON result");
                return Collections.emptyList();
            }
        } catch (JsonProcessingException e) {
            log.error("parsing LLM response into a JSON: {}", content, e);
            return Collections.emptyList();
        }
        var spliterator = Spliterators.spliteratorUnknownSize(
                structuredResponse.properties().iterator(), Spliterator.ORDERED | Spliterator.NONNULL);
        List<FeedbackScoreBatchItem> results = StreamSupport.stream(spliterator, false)
                .map(scoreMetric -> {
                    var scoreName = scoreMetric.getKey();
                    var scoreNested = scoreMetric.getValue();
                    if (scoreNested == null || scoreNested.isMissingNode() || !scoreNested.has(SCORE_FIELD_NAME)) {
                        log.info("No score found for '{}' score in {}", scoreName, scoreNested);
                        return null;
                    }
                    var resultBuilder = FeedbackScoreBatchItem.builder()
                            .name(scoreName)
                            .reason(scoreNested.path(REASON_FIELD_NAME).asText())
                            .source(ScoreSource.ONLINE_SCORING);
                    var actualScore = scoreNested.path(SCORE_FIELD_NAME);
                    if (actualScore.isBoolean()) {
                        resultBuilder.value(actualScore.asBoolean() ? BigDecimal.ONE : BigDecimal.ZERO);
                    } else {
                        resultBuilder.value(actualScore.decimalValue());
                    }
                    return resultBuilder.build();
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (results.isEmpty()) {
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
        return results;
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
}
