package com.comet.opik.domain.mapping;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.proto.common.v1.AnyValue;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Utility class for mapping and extracting fields in the context of OpenTelemetry data.
 * Provides methods for handling JSON data, parsing complex values, and mapping usage-related fields
 * for analytics or monitoring purposes.
 */
@Slf4j
public class OpenTelemetryMappingUtils {

    private static final Map<String, String> USAGE_KEYS_MAPPING = Map.of(
            "input_tokens", "prompt_tokens",
            "output_tokens", "completion_tokens");

    /**
     * Extracts a value from an AnyValue object and writes it to a specified JSON field in an ObjectNode.
     * Depending on the type and format of the value, the method handles it as textual data,
     * numeric data, boolean data, or an array, and converts it accordingly.
     *
     * @param node the JSON node where the data should be written
     * @param key the key used to add the extracted value to the JSON node
     * @param value the AnyValue object containing the value to be extracted and written
     */
    public static void extractToJsonColumn(ObjectNode node, String key, @NonNull AnyValue value) {
        switch (value.getValueCase()) {
            case STRING_VALUE -> {
                var stringValue = value.getStringValue();
                // check if a string value is actually a string or a stringfied json
                if (stringValue.startsWith("\"") || stringValue.startsWith("[")
                        || stringValue.startsWith("{")) {
                    try {
                        var jsonNode = JsonUtils.getJsonNodeFromString(stringValue);
                        if (jsonNode.isTextual()) {
                            try {
                                jsonNode = JsonUtils.getJsonNodeFromString(jsonNode.asText());
                            } catch (UncheckedIOException e) {
                                log.warn("Failed to parse nested JSON string for key {}: {}. Using as plain text.",
                                        key, e.getMessage());
                                node.put(key, jsonNode.asText());
                                return;
                            }
                        }
                        node.set(key, jsonNode);
                    } catch (UncheckedIOException e) {
                        log.warn("Failed to parse JSON string for key {}: {}. Using as plain text.", key,
                                e.getMessage());
                        node.put(key, stringValue);
                    }
                } else {
                    node.put(key, stringValue);
                }
            }
            case INT_VALUE -> node.put(key, value.getIntValue());
            case DOUBLE_VALUE -> node.put(key, value.getDoubleValue());
            case BOOL_VALUE -> node.put(key, value.getBoolValue());
            case ARRAY_VALUE -> {
                var array = JsonUtils.createArrayNode();
                value.getArrayValue().getValuesList().forEach(val -> array.add(val.getStringValue()));
                node.set(key, array);
            }
            default -> log.warn("Unsupported attribute: {} -> {}", key, value);
        }
    }

    /**
     * Extracts usage-related fields from a given value and adds them to the usage map.
     * The method supports extracting usage from integer values, string values, and JSON objects.
     *
     * @param usage the map where extracted usage fields will be stored
     * @param rule the mapping rule used to process the key and value
     * @param key the attribute key associated with the value
     * @param value the value to be processed and extracted
     */
    public static void extractUsageField(@NonNull Map<String, Integer> usage, @NonNull OpenTelemetryMappingRule rule,
            @NonNull String key, @NonNull AnyValue value) {
        // usage might appear as single int or string values as well as a JSON object
        if (value.hasIntValue()) {
            var actualKey = extractUsageMapKey(rule, key);
            usage.put(USAGE_KEYS_MAPPING.getOrDefault(actualKey, actualKey), (int) value.getIntValue());
        } else if (value.hasStringValue()) {
            boolean extracted = tryExtractUsageFromString(usage, rule, key, value.getStringValue());
            if (!extracted) {
                // extracting from a JSON object
                tryExtractUsageFromJsonObject(usage, key, value.getStringValue());
            }
        }
    }

    /**
     * Extracts tags from an AnyValue and returns them as a list of strings.
     * Supports extracting tags from string values (comma-separated), array values, and JSON arrays.
     *
     * @param value the AnyValue containing tag data
     * @return a list of extracted tag strings, empty if no valid tags found
     */
    public static List<String> extractTags(@NonNull AnyValue value) {
        switch (value.getValueCase()) {
            case STRING_VALUE -> {
                var stringValue = value.getStringValue();

                // Check if it's a JSON array string
                if (stringValue.startsWith("[") && stringValue.endsWith("]")) {
                    try {
                        JsonNode arrayNode = JsonUtils.getJsonNodeFromString(stringValue);
                        if (arrayNode.isArray()) {
                            List<String> tags = new ArrayList<>();
                            arrayNode.forEach(node -> {
                                if (node.isTextual()) {
                                    String tag = node.asText().trim();
                                    if (!tag.isEmpty()) {
                                        tags.add(tag);
                                    }
                                }
                            });
                            return tags;
                        }
                    } catch (UncheckedIOException e) {
                        log.debug("Failed to parse JSON array for tags: {}. Treating as comma-separated string.",
                                e.getMessage());
                    }
                }

                // Treat as a comma-separated string
                return Stream.of(stringValue.split(","))
                        .map(String::trim)
                        .filter(tag -> !tag.isEmpty())
                        .toList();
            }

            case ARRAY_VALUE -> {
                return value.getArrayValue().getValuesList().stream()
                        .filter(AnyValue::hasStringValue)
                        .map(AnyValue::getStringValue)
                        .map(String::trim)
                        .filter(tag -> !tag.isEmpty())
                        .toList();
            }

            default -> {
                log.warn("Unsupported value type for tags extraction: {}", value.getValueCase());
                return List.of();
            }
        }
    }

    /**
     * Attempts to parse a string value as an integer and add it to the usage map.
     *
     * @param usage       the usage map to update
     * @param rule        the mapping rule being processed
     * @param key         the original attribute key
     * @param stringValue the string value to parse
     * @return true if the string was successfully parsed and added, false otherwise
     */
    private static boolean tryExtractUsageFromString(Map<String, Integer> usage, OpenTelemetryMappingRule rule,
            String key, String stringValue) {
        try {
            int intValue = Integer.parseInt(stringValue);
            var actualKey = extractUsageMapKey(rule, key);
            usage.put(USAGE_KEYS_MAPPING.getOrDefault(actualKey, actualKey), intValue);
            return true;
        } catch (NumberFormatException e) {
            log.debug("Failed to parse usage string value '{}' for key '{}' as integer", stringValue, key);
            return false;
        }
    }

    /**
     * Extracts usage fields from a JSON object string and adds them to the usage map.
     *
     * @param usage       the usage map to update
     * @param key         the original attribute key (for error logging)
     * @param stringValue the JSON string to parse
     */
    private static void tryExtractUsageFromJsonObject(Map<String, Integer> usage, String key, String stringValue) {
        try {
            JsonNode usageNode = JsonUtils.getJsonNodeFromString(stringValue);
            if (usageNode.isTextual()) {
                try {
                    usageNode = JsonUtils.getJsonNodeFromString(usageNode.asText());
                } catch (UncheckedIOException e) {
                    log.warn(
                            "Failed to parse nested JSON string for usage field {}: {}. Skipping usage extraction.",
                            key, e.getMessage());
                    return;
                }
            }

            // we expect only integers for usage fields
            usageNode.properties().forEach(entry -> {
                if (entry.getValue().isNumber()) {
                    usage.put(
                            USAGE_KEYS_MAPPING.getOrDefault(entry.getKey(), entry.getKey()),
                            entry.getValue().intValue());
                } else {
                    log.warn("Unrecognized usage attribute {}: {}", entry.getKey(), entry.getValue());
                }
            });
        } catch (UncheckedIOException ex) {
            log.warn("Failed to parse JSON string for usage field {}: {}. Skipping usage extraction.", key,
                    ex.getMessage());
        }
    }

    private static String extractUsageMapKey(OpenTelemetryMappingRule rule, String key) {
        if (key.equals(rule.getRule()) && key.startsWith("gen_ai.usage.")) {
            return key.substring("gen_ai.usage.".length());
        }

        return key.substring(rule.getRule().length());
    }
}
