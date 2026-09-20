package com.comet.opik.domain;

import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.VariablePathUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Slf4j
@UtilityClass
public class FieldMappingResolver {

    public static Map<String, JsonNode> apply(
            @NonNull Map<String, JsonNode> enrichedData,
            @NonNull Object entity,
            @NonNull Map<String, String> fieldMappings) {

        if (fieldMappings.isEmpty()) {
            return enrichedData;
        }

        var data = new LinkedHashMap<>(enrichedData);
        data.keySet().removeAll(fieldMappings.keySet());
        data.putAll(resolve(entity, fieldMappings));
        return data;
    }

    public static Map<String, JsonNode> resolve(
            @NonNull Object entity,
            @NonNull Map<String, String> fieldMappings) {

        if (fieldMappings.isEmpty()) {
            return Map.of();
        }

        DocumentContext document;
        try {
            document = JsonPath.parse(JsonUtils.getMapper().convertValue(entity, Object.class));
        } catch (IllegalArgumentException exception) {
            log.warn("Failed to convert entity for field mapping", exception);
            return Map.of();
        }

        var resolved = new LinkedHashMap<String, JsonNode>();
        fieldMappings.forEach((name, path) -> read(document, path).ifPresent(value -> resolved.put(name, value)));
        return resolved;
    }

    public static String toJsonPath(@NonNull String path) {
        if (path.startsWith("$")) {
            return path;
        }
        return path.startsWith("[") ? "$" + path : "$." + path;
    }

    private static Optional<JsonNode> read(DocumentContext document, String path) {
        String jsonPath = toJsonPath(path);

        var unsupported = VariablePathUtils.findUnsupportedConstructInJsonPath(jsonPath);
        if (unsupported.isPresent()) {
            log.warn("Unsupported construct '{}' in field mapping, dropping field, path='{}'",
                    unsupported.get(), path);
            return Optional.empty();
        }

        try {
            return Optional.ofNullable(document.read(jsonPath))
                    .filter(value -> !(value instanceof Collection<?> collection) || !collection.isEmpty())
                    .map(JsonUtils::valueToTree);
        } catch (Exception exception) {
            log.debug("Could not resolve field mapping path='{}': {}", path, exception.getMessage());
            return Optional.empty();
        }
    }
}
