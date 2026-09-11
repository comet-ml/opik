package com.comet.opik.domain.evaluators;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.filter.Field;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.utils.JsonUtils;
import com.jayway.jsonpath.Configuration;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.Option;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;

import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Base class for filter evaluation services using JSONPath for nested field extraction.
 * Contains common logic for evaluating filters against entities (Trace, Span, etc.).
 * Subclasses must implement extractFieldValue to provide entity-specific field extraction.
 *
 * @param <E> the entity type (e.g., Trace, Span)
 */
@Slf4j
@RequiredArgsConstructor
public abstract class FilterEvaluationServiceBase<E> {

    // JSONPath configuration with options to suppress exceptions for missing paths
    // This static Configuration instance is reused across all calls for better performance
    private static final Configuration JSON_PATH_CONFIG = Configuration.builder()
            .options(Option.DEFAULT_PATH_LEAF_TO_NULL, Option.SUPPRESS_EXCEPTIONS)
            .build();

    // Pattern for converting numeric dot notation to bracket notation (e.g., "messages.0.content" -> "messages[0].content")
    private static final Pattern NUMERIC_DOT_PATTERN = Pattern.compile("\\.(\\d+)(?=\\.|$)");

    private final Class<E> entityClass;

    /**
     * Extracts the value of a field from an entity object.
     * This method must be implemented by subclasses to provide entity-specific field extraction.
     *
     * @param field the field to extract
     * @param key optional key for nested field extraction (e.g., metadata keys, feedback score names)
     * @param entity the entity to extract the field from
     * @return the extracted field value, or null if not found
     */
    protected abstract Object extractFieldValue(Field field, String key, E entity);

    /**
     * Gets the entity ID for logging purposes.
     *
     * @param entity the entity
     * @return the entity ID as a string
     */
    protected abstract String getEntityId(E entity);

    /**
     * Gets the entity class name for logging purposes.
     *
     * @return the entity class name
     */
    protected String getEntityClassName() {
        // Extract the actual entity type from the generic parameter
        return entityClass.getSimpleName();
    }

    /**
     * Evaluates whether an entity matches all the provided filters.
     * All filters must match for the entity to be considered a match (AND logic).
     *
     * @param filters the list of filters to evaluate
     * @param entity the entity to evaluate against
     * @return true if the entity matches all filters, false otherwise
     */
    public boolean matchesAllFilters(List<? extends Filter> filters, E entity) {
        if (filters.isEmpty()) {
            return true; // Empty filter list means all entities match
        }

        return filters.stream().allMatch(filter -> matchesFilter(filter, entity));
    }

    /**
     * Evaluates whether an entity matches a single filter.
     *
     * @param filter the filter to evaluate
     * @param entity the entity to evaluate against
     * @return true if the entity matches the filter, false otherwise
     */
    public boolean matchesFilter(Filter filter, E entity) {
        try {
            Field field = filter.field();
            Object fieldValue = extractFieldValue(field, filter.key(), entity);

            return evaluateOperator(filter.operator(), fieldValue, filter.value());
        } catch (Exception e) {
            log.warn("Error evaluating filter '{}' against {} '{}'", filter, getEntityClassName(),
                    getEntityId(entity), e);
            return false; // If we can't evaluate the filter, consider it a non-match
        }
    }

    /**
     * Extracts a string value from a JSON object/string.
     * Note: JSON operations are blocking. For reactive contexts, consider wrapping calls in Mono.fromCallable()
     * and using subscribeOn(Schedulers.boundedElastic()) to offload blocking operations.
     */
    protected String extractStringFromJson(Object jsonValue) {
        if (jsonValue == null) {
            return null;
        }
        if (jsonValue instanceof String str) {
            return str;
        }
        try {
            return JsonUtils.writeValueAsString(jsonValue);
        } catch (UncheckedIOException e) {
            log.warn("Failed to convert value to string", e);
            return jsonValue.toString();
        }
    }

    /**
     * Extracts a nested value from a JSON object using JSONPath.
     * Supports standard JSONPath syntax, e.g., "$.messages[0].content" or "messages[0].content".
     * Also supports numeric dot notation (e.g., "messages.0.content") which is converted to bracket notation.
     * See: https://github.com/json-path/JsonPath for full JSONPath syntax.
     * Note: JSON operations are blocking. For reactive contexts, consider wrapping calls in Mono.fromCallable()
     * and using subscribeOn(Schedulers.boundedElastic()) to offload blocking operations.
     */
    protected Object extractNestedValue(Object jsonValue, String key) {
        if (ObjectUtils.anyNull(jsonValue, key)) {
            return null;
        }

        // Normalize numeric dot notation to bracket notation (e.g., "messages.0.content" -> "messages[0].content")
        String normalizedKey = normalizeJsonPath(key);

        // Normalize the path to ensure it starts with $. for JSONPath
        // If key is blank, return root path "$" instead of "$." which JsonPath rejects
        String jsonPath;
        if (StringUtils.isBlank(normalizedKey)) {
            jsonPath = "$";
        } else {
            jsonPath = normalizedKey.startsWith("$") ? normalizedKey : "$." + normalizedKey;
        }

        try {
            String jsonString;
            if (jsonValue instanceof String str) {
                jsonString = str;
            } else {
                // Convert object to JSON string for JSONPath parsing
                jsonString = JsonUtils.writeValueAsString(jsonValue);
            }

            // Use JSONPath to extract the value
            // JSON_PATH_CONFIG is static and reused, providing better performance than creating a new Configuration each time
            return JsonPath.using(JSON_PATH_CONFIG).parse(jsonString).read(jsonPath);
        } catch (Exception e) {
            log.warn("Failed to extract nested value with JSONPath '{}'", jsonPath, e);
            return null;
        }
    }

    /**
     * Normalizes JSONPath by converting numeric dot notation to bracket notation.
     * For example: "messages.0.content" -> "messages[0].content"
     * This ensures compatibility with JSONPath library which prefers bracket notation for array indices.
     */
    private String normalizeJsonPath(String path) {
        if (StringUtils.isBlank(path)) {
            return path;
        }

        // Remove leading $ if present, we'll add it back later
        boolean hasLeadingDollar = path.startsWith("$");
        String pathWithoutDollar = hasLeadingDollar ? path.substring(1) : path;

        // Pattern: match a dot followed by one or more digits (array index in dot notation)
        // Replace ".0" with "[0]", ".123" with "[123]", etc.
        // Use word boundary to avoid matching digits that are part of property names
        String normalized = NUMERIC_DOT_PATTERN.matcher(pathWithoutDollar).replaceAll("[$1]");

        return hasLeadingDollar ? "$" + normalized : normalized;
    }

    /**
     * Extracts a usage value from the usage map.
     * Handles both Integer and Long values.
     */
    protected Number extractUsageValue(Map<String, ?> usage, String key) {
        if (usage == null || key == null) {
            return null;
        }
        Object value = usage.get(key);
        if (value instanceof Number num) {
            return num;
        }
        return null;
    }

    /**
     * Extracts a feedback score value by name.
     */
    protected Number extractFeedbackScore(List<FeedbackScore> feedbackScores, String scoreName) {
        if (feedbackScores == null || scoreName == null) {
            return null;
        }
        return feedbackScores.stream()
                .filter(score -> scoreName.equals(score.name()))
                .findFirst()
                .map(FeedbackScore::value)
                .orElse(null);
    }

    /**
     * Calculates duration between start and end time in milliseconds.
     */
    protected Number calculateDuration(Instant startTime, Instant endTime) {
        if (startTime == null || endTime == null) {
            return null;
        }
        return endTime.toEpochMilli() - startTime.toEpochMilli();
    }

    /**
     * Evaluates an operator against field value and filter value.
     */
    protected boolean evaluateOperator(Operator operator, Object fieldValue, String filterValue) {
        // Handle date/time fields specifically
        if (fieldValue instanceof Instant) {
            return evaluateDateTimeOperator(operator, (Instant) fieldValue, filterValue);
        }

        return switch (operator) {
            case EQUAL -> evaluateEquals(fieldValue, filterValue);
            case NOT_EQUAL -> !evaluateEquals(fieldValue, filterValue);
            case CONTAINS -> evaluateContains(fieldValue, filterValue);
            case NOT_CONTAINS -> !evaluateContains(fieldValue, filterValue);
            case GREATER_THAN -> evaluateGreaterThan(fieldValue, filterValue);
            case GREATER_THAN_EQUAL -> evaluateGreaterThanEqual(fieldValue, filterValue);
            case LESS_THAN -> evaluateLessThan(fieldValue, filterValue);
            case LESS_THAN_EQUAL -> evaluateLessThanEqual(fieldValue, filterValue);
            case IS_EMPTY -> evaluateIsEmpty(fieldValue);
            case IS_NOT_EMPTY -> !evaluateIsEmpty(fieldValue);
            default -> {
                log.warn("Unsupported operator for filter evaluation: {}", operator);
                yield false;
            }
        };
    }

    /**
     * Evaluates date/time operators specifically for Instant fields.
     */
    protected boolean evaluateDateTimeOperator(Operator operator, Instant fieldValue, String filterValue) {
        try {
            // Parse the filter value as an Instant (expected to be in ISO format from frontend)
            Instant filterInstant = Instant.parse(filterValue);

            return switch (operator) {
                case EQUAL -> fieldValue != null && fieldValue.equals(filterInstant);
                case NOT_EQUAL -> fieldValue == null || !fieldValue.equals(filterInstant);
                case GREATER_THAN -> fieldValue != null && fieldValue.isAfter(filterInstant);
                case GREATER_THAN_EQUAL -> fieldValue != null && !fieldValue.isBefore(filterInstant);
                case LESS_THAN -> fieldValue != null && fieldValue.isBefore(filterInstant);
                case LESS_THAN_EQUAL -> fieldValue != null && !fieldValue.isAfter(filterInstant);
                case IS_EMPTY -> fieldValue == null;
                case IS_NOT_EMPTY -> fieldValue != null;
                default -> {
                    log.warn("Unsupported operator for date/time field: {}", operator);
                    yield false;
                }
            };
        } catch (Exception e) {
            log.warn("Invalid date/time filter value '{}': {}", filterValue, e.getMessage());
            return false;
        }
    }

    protected boolean evaluateEquals(Object fieldValue, String filterValue) {
        if (fieldValue == null) {
            return filterValue == null || filterValue.isEmpty();
        }
        return Objects.equals(fieldValue.toString(), filterValue);
    }

    protected boolean evaluateContains(Object fieldValue, String filterValue) {
        if (fieldValue == null || filterValue == null) {
            return false;
        }

        // Handle list/array fields (like tags)
        if (fieldValue instanceof List<?> list) {
            return list.stream().anyMatch(
                    item -> item != null && item.toString().toLowerCase().contains(filterValue.toLowerCase()));
        }

        // Handle set fields (like tags)
        if (fieldValue instanceof Set<?> set) {
            return set.stream().anyMatch(
                    item -> item != null && item.toString().toLowerCase().contains(filterValue.toLowerCase()));
        }

        // Handle string fields
        return fieldValue.toString().toLowerCase().contains(filterValue.toLowerCase());
    }

    protected boolean evaluateGreaterThan(Object fieldValue, String filterValue) {
        int comparison = compareNumbers(fieldValue, filterValue);
        // If comparison failed (sentinel values), the filter should not match
        return comparison != Integer.MIN_VALUE && comparison != Integer.MAX_VALUE && comparison > 0;
    }

    protected boolean evaluateGreaterThanEqual(Object fieldValue, String filterValue) {
        int comparison = compareNumbers(fieldValue, filterValue);
        // If comparison failed (sentinel values), the filter should not match
        return comparison != Integer.MIN_VALUE && comparison != Integer.MAX_VALUE && comparison >= 0;
    }

    protected boolean evaluateLessThan(Object fieldValue, String filterValue) {
        int comparison = compareNumbers(fieldValue, filterValue);
        // If comparison failed (sentinel values), the filter should not match
        return comparison != Integer.MIN_VALUE && comparison != Integer.MAX_VALUE && comparison < 0;
    }

    protected boolean evaluateLessThanEqual(Object fieldValue, String filterValue) {
        int comparison = compareNumbers(fieldValue, filterValue);
        // If comparison failed (sentinel values), the filter should not match
        return comparison != Integer.MIN_VALUE && comparison != Integer.MAX_VALUE && comparison <= 0;
    }

    protected boolean evaluateIsEmpty(Object fieldValue) {
        if (fieldValue == null) {
            return true;
        }
        if (fieldValue instanceof String str) {
            return str.isEmpty();
        }
        if (fieldValue instanceof List<?> list) {
            return list.isEmpty();
        }
        if (fieldValue instanceof Set<?> set) {
            return set.isEmpty();
        }
        if (fieldValue instanceof Map<?, ?> map) {
            return map.isEmpty();
        }
        return false;
    }

    /**
     * Compare two values as numbers. Returns a sentinel value if they can't be compared as numbers.
     *
     * @return positive number if field > filter, negative if field < filter, 0 if equal
     * @return Integer.MIN_VALUE if field cannot be converted to number (should fail comparison)
     * @return Integer.MAX_VALUE if filter cannot be converted to number (should fail comparison)
     */
    protected int compareNumbers(Object fieldValue, String filterValue) {
        try {
            BigDecimal fieldNumber = convertToNumber(fieldValue);
            BigDecimal filterNumber = new BigDecimal(filterValue);

            if (fieldNumber == null) {
                // Field value cannot be converted to number - this should make the comparison fail
                return Integer.MIN_VALUE;
            }

            return fieldNumber.compareTo(filterNumber);
        } catch (NumberFormatException e) {
            log.warn("Failed to compare values as numbers: {} vs {}", fieldValue, filterValue);
            // Filter value cannot be converted to number - this should make the comparison fail
            return Integer.MAX_VALUE;
        }
    }

    /**
     * Convert a value to BigDecimal for numeric comparison.
     */
    protected BigDecimal convertToNumber(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number num) {
            return new BigDecimal(num.toString());
        }

        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            log.warn("{}:convertToNumber() failed for value: {}", getEntityClassName(), value);
            return null;
        }
    }
}
