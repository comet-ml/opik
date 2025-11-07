package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.PromptType;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
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
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.data.video.Video;
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

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final Pattern JSON_BLOCK_PATTERN = Pattern.compile(
            "```(?:json)?\\s*(\\{.*?})\\s*```", Pattern.DOTALL);
    private static final Pattern MEDIA_PLACEHOLDER_PATTERN = Pattern.compile(
            "<<<(image|video)>>>(.*?)<<</(image|video)>>>",
            Pattern.DOTALL);

    /**
     * Prepare a request to a LLM-as-Judge evaluator (a ChatLanguageModel) rendering the template messages with
     * Trace variables and with the proper structured output format.
     *
     * @param evaluatorCode the LLM-as-Judge 'code'
     * @param trace         the sampled Trace to be scored
     * @return a request to trigger to any supported provider with a ChatLanguageModel
     */
    public static ChatRequest prepareLlmRequest(
            @NotNull LlmAsJudgeCode evaluatorCode, Trace trace, StructuredOutputStrategy structuredOutputStrategy) {
        var renderedMessages = renderMessages(evaluatorCode.messages(), evaluatorCode.variables(), trace);
        var chatRequestBuilder = ChatRequest.builder().messages(renderedMessages);

        return structuredOutputStrategy.apply(chatRequestBuilder, renderedMessages, evaluatorCode.schema()).build();
    }

    /**
     * Prepare a request to a LLM-as-Judge evaluator (a ChatLanguageModel) rendering the template messages with
     * Trace variables and with the proper structured output format.
     *
     * @param evaluatorCode the LLM-as-Judge 'code'
     * @param traces        the sampled traces from the trace threads to be scored
     * @return a request to trigger to any supported provider with a ChatLanguageModel
     */
    public static ChatRequest prepareThreadLlmRequest(
            @NotNull TraceThreadLlmAsJudgeCode evaluatorCode, @NotNull List<Trace> traces,
            @NotNull StructuredOutputStrategy structuredOutputStrategy) {
        var renderedMessages = renderThreadMessages(evaluatorCode.messages(),
                Map.of(TraceThreadLlmAsJudgeCode.CONTEXT_VARIABLE_NAME, ""), traces);
        var chatRequestBuilder = ChatRequest.builder().messages(renderedMessages);

        return structuredOutputStrategy.apply(chatRequestBuilder, renderedMessages, evaluatorCode.schema()).build();
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
                    var renderedMessage = TemplateParseUtils.render(
                            templateMessage.content(), replacements, PromptType.MUSTACHE);
                    return switch (templateMessage.role()) {
                        case USER -> buildUserMessage(renderedMessage);
                        case SYSTEM -> SystemMessage.from(renderedMessage);
                        default -> {
                            log.info("No mapping for message role type {}", templateMessage.role());
                            yield null;
                        }
                    };
                })
                .filter(Objects::nonNull)
                .toList();
    }

    /**
     * Render the rule evaluator message template using the values from an actual trace.
     * <p>
     * As the rule may consist in multiple messages, we check each one of them for variables to fill.
     * Then we go through every variable template to replace them for the value from the trace.
     *
     * @param templateMessages a list of messages with variables to fill with a Trace value
     * @param variablesMap     a map of template variable to a path to a value into a Trace
     * @param trace            the trace with value to use to replace template variables
     * @return a list of AI messages, with templates rendered
     */
    static List<ChatMessage> renderMessages(
            List<LlmAsJudgeMessage> templateMessages, Map<String, String> variablesMap, Trace trace) {
        // prepare the map of replacements to use in all messages
        Map<String, String> replacements = toReplacements(variablesMap, trace);
        // render the message templates from evaluator rule
        return templateMessages.stream()
                .map(templateMessage -> {
                    // will convert all '{{key}}' into 'value'
                    var renderedMessage = TemplateParseUtils.render(
                            templateMessage.content(), replacements, PromptType.MUSTACHE);
                    return switch (templateMessage.role()) {
                        case USER -> buildUserMessage(renderedMessage);
                        case SYSTEM -> SystemMessage.from(renderedMessage);
                        default -> {
                            log.info("No mapping for message role type {}", templateMessage.role());
                            yield null;
                        }
                    };
                })
                .filter(Objects::nonNull)
                .toList();
    }

    public static Map<String, String> toReplacements(Map<String, String> variables, Trace trace) {
        var parsedVariables = toVariableMapping(variables);
        // extract the actual value from the Trace
        return parsedVariables.stream().map(mapper -> {
            var traceSection = switch (mapper.traceSection()) {
                case INPUT -> trace.input();
                case OUTPUT -> trace.output();
                case METADATA -> trace.metadata();
                case null -> null;
            };
            // if no trace section, there's no replacement and the literal value is taken
            var valueToReplace = traceSection != null
                    ? extractFromJson(traceSection, mapper.jsonPath())
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
     * @param evaluatorVariables a map with variables and a path into a trace input/output/metadata to replace
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
                            .filter(traceSection -> tracePath.startsWith(traceSection.prefix))
                            .findFirst()
                            .ifPresentOrElse(traceSection -> builder.traceSection(traceSection)
                                    .jsonPath("$." + tracePath.substring(traceSection.prefix.length())),
                                    // if not a trace section, it's a literal value to replace
                                    () -> builder.valueToReplace(tracePath));

                    return builder.build();
                })
                .filter(Objects::nonNull)
                .toList();
    }

    private UserMessage buildUserMessage(String content) {
        var matcher = MEDIA_PLACEHOLDER_PATTERN.matcher(content);

        if (!matcher.find()) {
            return UserMessage.from(content);
        }

        matcher.reset();

        var builder = UserMessage.builder();
        var lastIndex = 0;

        while (matcher.find()) {
            if (matcher.start() > lastIndex) {
                var textSegment = content.substring(lastIndex, matcher.start());
                appendTextContent(builder, textSegment);
            }

            var mediaType = matcher.group(1);
            var rawValue = matcher.group(2).trim();
            var placeholderText = matcher.group(0);

            if (!rawValue.isEmpty()) {
                // Mustache templates can HTML-escape URLs; unescape before building structured content
                // so providers receive the exact user-specified payload.
                var unescapedValue = StringEscapeUtils.unescapeHtml4(rawValue);
                var mediaContent = createMediaContent(mediaType, unescapedValue);
                if (mediaContent != null) {
                    builder.addContent(mediaContent);
                } else {
                    appendTextContent(builder, placeholderText);
                }
            }

            lastIndex = matcher.end();
        }

        if (lastIndex < content.length()) {
            var trailingText = content.substring(lastIndex);
            appendTextContent(builder, trailingText);
        }

        return builder.build();
    }

    private Content createMediaContent(String mediaType, String rawValue) {
        if ("image".equalsIgnoreCase(mediaType)) {
            return createImageContent(rawValue);
        }

        if ("video".equalsIgnoreCase(mediaType)) {
            return createVideoContent(rawValue);
        }

        log.warn("Unsupported media type placeholder encountered: '{}'", mediaType);
        return null;
    }

    private void appendTextContent(UserMessage.Builder builder, String textSegment) {
        if (StringUtils.isNotBlank(textSegment)) {
            builder.addContent(TextContent.from(textSegment));
        }
    }

    private Content createImageContent(String rawValue) {
        if (StringUtils.isBlank(rawValue)) {
            return null;
        }

        try {
            return ImageContent.from(rawValue);
        } catch (IllegalArgumentException exception) {
            log.warn("Failed to build image content for placeholder: {}", rawValue, exception);
            return null;
        }
    }

    private Content createVideoContent(String rawValue) {
        var trimmed = StringUtils.trimToEmpty(rawValue);
        if (trimmed.isEmpty()) {
            return null;
        }

        if (looksLikeJson(trimmed)) {
            Object parsed = tryParseJson(trimmed);
            var fromJson = convertVideoPayload(parsed);
            if (fromJson != null) {
                return fromJson;
            }
        }

        return buildVideoFromValue(trimmed, null);
    }

    private Content convertVideoPayload(Object payload) {
        if (payload instanceof Map<?, ?> mapPayload) {
            return convertVideoObject(mapPayload);
        }

        if (payload instanceof List<?> listPayload) {
            for (var entry : listPayload) {
                var converted = convertVideoPayload(entry);
                if (converted != null) {
                    return converted;
                }
            }
        }

        if (payload instanceof String stringPayload) {
            return buildVideoFromValue(stringPayload, null);
        }

        return null;
    }

    private Content convertVideoObject(Map<?, ?> payload) {
        var type = stringValue(payload.get("type"));

        if ("video_url".equalsIgnoreCase(type) || payload.containsKey("video_url")) {
            return buildVideoFromVideoUrl(payload.get("video_url"));
        }

        if ("file".equalsIgnoreCase(type) || payload.containsKey("file")) {
            return buildVideoFromFile(payload.get("file"));
        }

        if (type == null) {
            return buildVideoFromVideoUrl(payload.get("url"));
        }

        return null;
    }

    private Content buildVideoFromVideoUrl(Object videoNode) {
        if (videoNode instanceof String url) {
            return buildVideoFromValue(url, null);
        }

        if (videoNode instanceof Map<?, ?> videoMap) {
            var url = stringValue(videoMap.get("url"));
            var mimeType = firstNonBlank(
                    stringValue(videoMap.get("mime_type")),
                    stringValue(videoMap.get("format")));
            return buildVideoFromValue(url, mimeType);
        }

        return null;
    }

    private Content buildVideoFromFile(Object fileNode) {
        if (!(fileNode instanceof Map<?, ?> fileMap)) {
            return null;
        }

        var fileId = stringValue(fileMap.get("file_id"));
        if (StringUtils.isNotBlank(fileId)) {
            return buildVideoFromValue(fileId, stringValue(fileMap.get("format")));
        }

        var fileData = stringValue(fileMap.get("file_data"));
        if (StringUtils.isNotBlank(fileData)) {
            return buildVideoFromBase64(fileData, stringValue(fileMap.get("format")));
        }

        return null;
    }

    private Content buildVideoFromValue(String value, String mimeType) {
        if (StringUtils.isBlank(value)) {
            return null;
        }

        if (value.startsWith("data:video/")) {
            return buildVideoFromUrl(value, mimeType);
        }

        if (value.startsWith("http://") || value.startsWith("https://")) {
            return buildVideoFromUrl(value, mimeType);
        }

        return buildVideoFromBase64(value, mimeType);
    }

    private Content buildVideoFromUrl(String url, String mimeType) {
        if (StringUtils.isBlank(url)) {
            return null;
        }

        try {
            if (StringUtils.isBlank(mimeType)) {
                return VideoContent.from(url);
            }

            var video = Video.builder()
                    .url(url)
                    .mimeType(mimeType)
                    .build();
            return VideoContent.from(video);
        } catch (IllegalArgumentException exception) {
            log.warn("Failed to build video content for url placeholder: {}", url, exception);
            return null;
        }
    }

    private Content buildVideoFromBase64(String base64Data, String mimeType) {
        if (StringUtils.isBlank(base64Data)) {
            return null;
        }

        var safeMimeType = StringUtils.defaultIfBlank(mimeType, "video/mp4");
        try {
            return VideoContent.from(base64Data, safeMimeType);
        } catch (IllegalArgumentException exception) {
            log.warn("Failed to build video content from base64 payload", exception);
            return null;
        }
    }

    private boolean looksLikeJson(String value) {
        var trimmed = StringUtils.trimToEmpty(value);
        return trimmed.startsWith("{") || trimmed.startsWith("[");
    }

    private Object tryParseJson(String rawValue) {
        try {
            return JsonUtils.readValue(rawValue, Object.class);
        } catch (RuntimeException exception) {
            log.warn("Failed to parse media placeholder as JSON: {}", rawValue, exception);
            return null;
        }
    }

    private String stringValue(Object value) {
        return value instanceof String str ? str : null;
    }

    private String firstNonBlank(String first, String second) {
        if (StringUtils.isNotBlank(first)) {
            return first;
        }
        if (StringUtils.isNotBlank(second)) {
            return second;
        }
        return null;
    }

    private static String extractFromJson(JsonNode json, String path) {
        Map<String, Object> forcedObject;
        try {
            // JsonPath didn't work with JsonNode, even explicitly using JacksonJsonProvider, so we convert to a Map
            forcedObject = OBJECT_MAPPER.convertValue(json, new TypeReference<>() {
            });
        } catch (InvalidArgumentException e) {
            log.warn("failed to parse json, json={}", json, e);
            return null;
        }

        try {
            return JsonPath.parse(forcedObject).read(path);
        } catch (Exception e) {
            log.warn("couldn't find path inside json, trying flat structure, path={}, json={}", path, json, e);
            return Optional.ofNullable(forcedObject.get(path.replace("$.", "")))
                    .map(Object::toString)
                    .orElseGet(() -> {
                        log.info("couldn't find flat or nested path in json, path={}, json={}", path, json);
                        return null;
                    });
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
