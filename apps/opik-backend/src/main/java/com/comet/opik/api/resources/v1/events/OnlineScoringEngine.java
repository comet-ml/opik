package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Trace;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.request.json.JsonBooleanSchema;
import dev.langchain4j.model.chat.request.json.JsonIntegerSchema;
import dev.langchain4j.model.chat.request.json.JsonNumberSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.request.json.JsonSchema;
import dev.langchain4j.model.chat.request.json.JsonSchemaElement;
import dev.langchain4j.model.chat.request.json.JsonStringSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringSubstitutor;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;
import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeMessage;
import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeOutputSchema;

@UtilityClass
@Slf4j
public class OnlineScoringEngine {

    static final String SCORE_FIELD_NAME = "score";
    static final String REASON_FIELD_NAME = "reason";
    static final String SCORE_FIELD_DESCRIPTION = "the score for ";
    static final String REASON_FIELD_DESCRIPTION = "the reason for the score for ";
    static final String DEFAULT_SCHEMA_NAME = "scoring_schema";

    /**
     * Prepare a request to a LLM-as-Judge evaluator (a ChatLanguageModel) rendering the template messages with
     * Trace variables and with the proper structured output format.
     *
     * @param evaluatorCode the LLM-as-Judge 'code'
     * @param trace the sampled Trace to be scored
     * @return a request to trigger to any supported provider with a ChatLanguageModel
     */
    public static ChatRequest prepareLlmRequest(@NotNull LlmAsJudgeCode evaluatorCode,
            Trace trace) {
        var responseFormat = toResponseFormat(evaluatorCode.schema());
        var renderedMessages = renderMessages(evaluatorCode.messages(), evaluatorCode.variables(), trace);

        return ChatRequest.builder()
                .messages(renderedMessages)
                .responseFormat(responseFormat)
                .build();
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
    static List<ChatMessage> renderMessages(List<LlmAsJudgeMessage> templateMessages, Map<String, String> variablesMap,
            Trace trace) {
        // prepare the map of replacements to use in all messages
        var parsedVariables = toVariableMapping(variablesMap);

        // extract the actual value from the Trace
        var replacements = parsedVariables.stream().map(mapper -> {
            var traceSection = switch (mapper.traceSection()) {
                case INPUT -> trace.input();
                case OUTPUT -> trace.output();
                case METADATA -> trace.metadata();
            };

            return mapper.toBuilder()
                    .valueToReplace(extractFromJson(traceSection, mapper.jsonPath()))
                    .build();
        })
                .filter(mapper -> mapper.valueToReplace() != null)
                .collect(
                        Collectors.toMap(MessageVariableMapping::variableName, MessageVariableMapping::valueToReplace));

        // will convert all '{{key}}' into 'value'
        // TODO: replace with Mustache Java to be in confirm with FE
        var templateRenderer = new StringSubstitutor(replacements, "{{", "}}");

        // render the message templates from evaluator rule
        return templateMessages.stream()
                .map(templateMessage -> {
                    var renderedMessage = templateRenderer.replace(templateMessage.content());

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
                            .ifPresent(traceSection -> builder.traceSection(traceSection)
                                    .jsonPath("$." + tracePath.substring(traceSection.prefix.length())));

                    return builder.build();
                })
                .filter(Objects::nonNull)
                .toList();
    }

    final ObjectMapper objectMapper = new ObjectMapper();

    String extractFromJson(JsonNode json, String path) {
        try {
            // JsonPath didnt work with JsonNode, even explicitly using JacksonJsonProvider, so we convert to a Map
            var forcedObject = objectMapper.convertValue(json, Map.class);
            return JsonPath.parse(forcedObject).read(path);
        } catch (Exception e) {
            log.debug("Couldn't find path '{}' inside json {}: {}", path, json, e.getMessage());
            return null;
        }
    }

    static ResponseFormat toResponseFormat(@NotNull List<LlmAsJudgeOutputSchema> schema) {
        // convert <name, type, description> into something like
        // "${name}": { "score": { "type": "${type}" , "description": ${description}", "reason": { "type" : "string" }}
        Map<String, JsonSchemaElement> structuredFields = schema.stream()
                .map(scoreDefinition -> Map.entry(scoreDefinition.name(),
                        JsonObjectSchema.builder()
                                .description(scoreDefinition.description())
                                .required(SCORE_FIELD_NAME, REASON_FIELD_NAME)
                                .properties(Map.of(
                                        SCORE_FIELD_NAME, switch (scoreDefinition.type()) {
                                            case BOOLEAN -> JsonBooleanSchema.builder()
                                                    .description(SCORE_FIELD_DESCRIPTION + scoreDefinition.name())
                                                    .build();
                                            case INTEGER -> JsonIntegerSchema.builder()
                                                    .description(SCORE_FIELD_DESCRIPTION + scoreDefinition.name())
                                                    .build();
                                            case DOUBLE -> JsonNumberSchema.builder()
                                                    .description(SCORE_FIELD_DESCRIPTION + scoreDefinition.name())
                                                    .build();
                                        },
                                        REASON_FIELD_NAME,
                                        JsonStringSchema.builder()
                                                .description(REASON_FIELD_DESCRIPTION + scoreDefinition.name())
                                                .build()))
                                .build()

                ))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        var allPropertyNames = structuredFields.keySet().stream().toList();

        var schemaBuilder = JsonObjectSchema.builder().required(allPropertyNames).properties(structuredFields).build();

        var jsonSchema = JsonSchema.builder().name(DEFAULT_SCHEMA_NAME).rootElement(schemaBuilder).build();

        return ResponseFormat.builder()
                .type(ResponseFormatType.JSON)
                .jsonSchema(jsonSchema)
                .build();
    }

    public static List<FeedbackScoreBatchItem> toFeedbackScores(@NotNull ChatResponse chatResponse) {
        var content = chatResponse.aiMessage().text();

        JsonNode structuredResponse;
        try {
            structuredResponse = objectMapper.readTree(content);
            if (!structuredResponse.isObject()) {
                log.info("ChatResponse content returned into an empty JSON result");
                return Collections.emptyList();
            }
        } catch (JsonProcessingException e) {
            log.error("parsing LLM response into a JSON: {}", content, e);
            return Collections.emptyList();
        }

        var spliterator = Spliterators.spliteratorUnknownSize(structuredResponse.fields(),
                Spliterator.ORDERED | Spliterator.NONNULL);

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
                    }
                    else {
                        resultBuilder.value(actualScore.decimalValue());
                    }

                    return resultBuilder.build();
                })
                .filter(Objects::nonNull)
                .toList();

    }

    @AllArgsConstructor
    public enum TraceSection {
        INPUT("input."),
        OUTPUT("output."),
        METADATA("metadata.");

        final String prefix;
    }

    @Builder(toBuilder = true)
    public record MessageVariableMapping(TraceSection traceSection, String variableName, String jsonPath,
            String valueToReplace) {
    }
}
