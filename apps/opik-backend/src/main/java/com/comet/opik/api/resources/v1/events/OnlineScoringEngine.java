package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.PromptType;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.domain.evaluators.python.TraceThreadPythonEvaluatorRequest;
import com.comet.opik.domain.llm.structuredoutput.StructuredOutputStrategy;
import com.comet.opik.utils.TemplateParseUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.gax.rpc.InvalidArgumentException;
import com.jayway.jsonpath.JsonPath;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

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

    /**
     * Prepare a request to a LLM-as-Judge evaluator (a ChatLanguageModel) rendering the template messages with
     * Trace variables and with the proper structured output format.
     *
     * @param evaluatorCode the LLM-as-Judge 'code'
     * @param trace the sampled Trace to be scored
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
     * @param traces the sampled traces from the trace threads to be scored
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
                        case USER -> UserMessage.from(renderedMessage);
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
                        case USER -> UserMessage.from(renderedMessage);
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
                structuredResponse.fields(), Spliterator.ORDERED | Spliterator.NONNULL);
        return StreamSupport.stream(spliterator, false)
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
