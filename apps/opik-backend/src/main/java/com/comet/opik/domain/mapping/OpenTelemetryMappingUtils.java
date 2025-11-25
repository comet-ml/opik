package com.comet.opik.domain.mapping;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.opentelemetry.proto.common.v1.AnyValue;
import lombok.extern.slf4j.Slf4j;

import java.io.UncheckedIOException;

@Slf4j
public class OpenTelemetryMappingUtils {

    public static void extractToJsonColumn(ObjectNode node, String key, AnyValue value) {
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
}
