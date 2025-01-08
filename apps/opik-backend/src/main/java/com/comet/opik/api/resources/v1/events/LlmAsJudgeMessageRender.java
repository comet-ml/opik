package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.Trace;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.JsonPath;
import dev.ai4j.openai4j.chat.Message;
import dev.ai4j.openai4j.chat.SystemMessage;
import dev.ai4j.openai4j.chat.UserMessage;
import lombok.Builder;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.text.StringSubstitutor;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@UtilityClass
@Slf4j
class LlmAsJudgeMessageRender {

    /**
     * Render the rule evaluator message template using the values from an actual trace.
     *
     * As the rule my consist in multiple messages, we check each one of them for variables to fill.
     * Then we go through every variable template to replace them for the value from the trace.
     *
     * @param trace the trace with value to use to replace template variables
     * @param evaluatorCode the evaluator
     * @return a list of AI messages, with templates rendered
     */
    public static List<Message> renderMessages(Trace trace,
            AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode evaluatorCode) {
        // prepare the map of replacements to use in all messages
        var parsedVariables = variableMapping(evaluatorCode.variables());

        // extract the actual value from the Trace
        var replacements = parsedVariables.stream().map(mapper -> {
            var traceSection = switch (mapper.traceSection) {
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
                        Collectors.toMap(LlmAsJudgeMessageRender.MessageVariableMapping::variableName,
                                LlmAsJudgeMessageRender.MessageVariableMapping::valueToReplace));

        // will convert all '{{key}}' into 'value'
        // TODO: replace with Mustache Java to be in confirm with FE
        var templateRenderer = new StringSubstitutor(replacements, "{{", "}}");

        // render the message templates from evaluator rule
        return evaluatorCode.messages().stream()
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
     * Parse evaluator\'s variable mapper into an usable list of
     *
     * @param evaluatorVariables a map with variables and a path into a trace input/output/metadata to replace
     * @return a parsed list of mappings, easier to use for the template rendering
     */
    public static List<MessageVariableMapping> variableMapping(Map<String, String> evaluatorVariables) {
        return evaluatorVariables.entrySet().stream()
                .map(mapper -> {
                    var templateVariable = mapper.getKey();
                    var tracePath = mapper.getValue();

                    var builder = MessageVariableMapping.builder().variableName(templateVariable);

                    if (tracePath.startsWith("input.")) {
                        builder.traceSection(TraceSection.INPUT)
                                .jsonPath("$" + tracePath.substring("input".length()));
                    } else if (tracePath.startsWith("output.")) {
                        builder.traceSection(TraceSection.OUTPUT)
                                .jsonPath("$" + tracePath.substring("output".length()));
                    } else if (tracePath.startsWith("metadata.")) {
                        builder.traceSection(TraceSection.METADATA)
                                .jsonPath("$" + tracePath.substring("metadata".length()));
                    } else {
                        log.info("Couldn't map trace path '{}' into a input/output/metadata path", tracePath);
                        return null;
                    }

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

    public enum TraceSection {
        INPUT,
        OUTPUT,
        METADATA
    }

    @Builder(toBuilder = true)
    public record MessageVariableMapping(TraceSection traceSection, String variableName, String jsonPath,
            String valueToReplace) {
    }
}
