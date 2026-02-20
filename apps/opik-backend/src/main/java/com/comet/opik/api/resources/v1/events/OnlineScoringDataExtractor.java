package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.PromptType;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.evaluators.LlmAsJudgeMessageContent;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TemplateParseUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.api.gax.rpc.InvalidArgumentException;
import com.jayway.jsonpath.JsonPath;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.List;

@UtilityClass
@Slf4j
class OnlineScoringDataExtractor {

    private static final ObjectMapper OBJECT_MAPPER = JsonUtils.getMapper();

    // --- Shared types ---

    @FunctionalInterface
    interface JsonSectionExtractor {
        JsonNode extract(TraceSection section);
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

    // --- Full section object data extraction ---

    static Map<String, Object> toFullSectionObjectData(Trace trace) {
        return toFullSectionObjectData(section -> switch (section) {
            case INPUT -> trace.input();
            case OUTPUT -> trace.output();
            case METADATA -> trace.metadata();
        });
    }

    static Map<String, Object> toFullSectionObjectData(Span span) {
        return toFullSectionObjectData(section -> switch (section) {
            case INPUT -> span.input();
            case OUTPUT -> span.output();
            case METADATA -> span.metadata();
        });
    }

    private static Map<String, Object> toFullSectionObjectData(JsonSectionExtractor sectionExtractor) {
        Map<String, Object> data = new HashMap<>();
        for (TraceSection section : TraceSection.values()) {
            JsonNode jsonSection = sectionExtractor.extract(section);
            if (jsonSection != null && !jsonSection.isNull()) {
                String key = section.prefix.substring(0, section.prefix.length() - 1);
                data.put(key, OBJECT_MAPPER.convertValue(jsonSection, Object.class));
            }
        }
        return Collections.unmodifiableMap(data);
    }

    // --- Template variable extraction ---

    /**
     * Extract all Mustache variables from a list of template messages.
     * Handles both string content and structured content (multimodal).
     *
     * @param templateMessages the messages to extract variables from
     * @return a set of all variable names found in the templates
     */
    static Set<String> extractAllVariablesFromMessages(List<LlmAsJudgeMessage> templateMessages) {
        return templateMessages.stream()
                .flatMap(message -> {
                    if (message.isStringContent()) {
                        return TemplateParseUtils.extractVariables(message.asString(), PromptType.MUSTACHE).stream();
                    } else if (message.isStructuredContent()) {
                        return message.asContentList().stream()
                                .flatMap(content -> {
                                    Stream.Builder<String> texts = Stream.builder();
                                    if (content.text() != null) {
                                        texts.add(content.text());
                                    }
                                    if (content.imageUrl() != null && content.imageUrl().url() != null) {
                                        texts.add(content.imageUrl().url());
                                    }
                                    if (content.videoUrl() != null && content.videoUrl().url() != null) {
                                        texts.add(content.videoUrl().url());
                                    }
                                    if (content.audioUrl() != null && content.audioUrl().url() != null) {
                                        texts.add(content.audioUrl().url());
                                    }
                                    return texts.build();
                                })
                                .flatMap(text -> TemplateParseUtils.extractVariables(text, PromptType.MUSTACHE)
                                        .stream());
                    }
                    return Stream.empty();
                })
                .collect(Collectors.toSet());
    }

    // --- Replacements from template variables ---

    /**
     * Extract replacements directly from template variables without requiring a variables mapping.
     * Variables in templates use dot-notation like {{input.question}}, {{output.answer}}, {{metadata.model}}.
     * The first segment (input/output/metadata) determines the trace section, and the rest is the JSON path.
     *
     * @param templateVariables set of variable names extracted from templates (e.g., "input.question")
     * @param trace             the trace to extract values from
     * @return a map of variable name to extracted value
     */
    public static Map<String, String> toReplacementsFromTemplateVariables(Set<String> templateVariables, Trace trace) {
        return toReplacementsFromTemplateVariables(templateVariables, section -> switch (section) {
            case INPUT -> trace.input();
            case OUTPUT -> trace.output();
            case METADATA -> trace.metadata();
        });
    }

    /**
     * Extract replacements directly from template variables for a Span.
     *
     * @param templateVariables set of variable names extracted from templates
     * @param span              the span to extract values from
     * @return a map of variable name to extracted value
     */
    public static Map<String, String> toReplacementsFromTemplateVariables(Set<String> templateVariables, Span span) {
        return toReplacementsFromTemplateVariables(templateVariables, section -> switch (section) {
            case INPUT -> span.input();
            case OUTPUT -> span.output();
            case METADATA -> span.metadata();
        });
    }

    private static Map<String, String> toReplacementsFromTemplateVariables(
            Set<String> templateVariables, JsonSectionExtractor sectionExtractor) {
        return templateVariables.stream()
                .map(variableName -> {
                    var mapping = parseVariableAsPath(variableName);
                    if (mapping == null) {
                        return null;
                    }
                    var section = mapping.traceSection();
                    var jsonSection = section != null ? sectionExtractor.extract(section) : null;
                    if (jsonSection == null) {
                        return null;
                    }
                    var valueToReplace = extractFromJson(jsonSection, mapping.jsonPath());
                    return mapping.toBuilder()
                            .valueToReplace(valueToReplace)
                            .build();
                })
                .filter(Objects::nonNull)
                .filter(mapping -> mapping.valueToReplace() != null)
                .collect(
                        Collectors.toMap(MessageVariableMapping::variableName, MessageVariableMapping::valueToReplace));
    }

    // --- Variable path parsing ---

    /**
     * Parse a template variable name as a path to trace data.
     * The variable name should be in dot-notation like "input.question" or "output.answer".
     * The first segment determines the trace section (input/output/metadata).
     *
     * @param variableName the variable name from the template (e.g., "input.question")
     * @return a MessageVariableMapping with the parsed section and JSON path, or null if invalid
     */
    static MessageVariableMapping parseVariableAsPath(String variableName) {
        if (StringUtils.isBlank(variableName)) {
            return null;
        }

        // Find which section this variable belongs to
        for (TraceSection section : TraceSection.values()) {
            String sectionName = section.prefix.substring(0, section.prefix.length() - 1); // Remove trailing dot
            if (variableName.equals(sectionName)) {
                // Variable is just the section name (e.g., "input") - return entire section
                return MessageVariableMapping.builder()
                        .variableName(variableName)
                        .traceSection(section)
                        .jsonPath("$")
                        .build();
            } else if (variableName.startsWith(section.prefix)) {
                // Variable has a path after section (e.g., "input.question")
                String jsonPath = "$." + variableName.substring(section.prefix.length());
                return MessageVariableMapping.builder()
                        .variableName(variableName)
                        .traceSection(section)
                        .jsonPath(jsonPath)
                        .build();
            }
        }

        // Variable doesn't match any section - could be a literal or invalid
        log.debug("Variable '{}' does not match any trace section (input/output/metadata)", variableName);
        return null;
    }

    // --- JSON extraction utilities ---

    static String extractFromJson(JsonNode json, String path) {
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
                    .map(OnlineScoringDataExtractor::serializeToJsonString)
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
}
