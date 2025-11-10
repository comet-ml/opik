package com.comet.opik.domain.evaluators;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.Trace;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.ObjectUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Service for evaluating filters against Trace objects in memory.
 * This is used by the online scoring system to filter traces before applying automation rules.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class TraceFilterEvaluationService {

    /**
     * Evaluates whether a trace matches all the provided filters.
     * All filters must match for the trace to be considered a match (AND logic).
     *
     * @param filters the list of filters to evaluate
     * @param trace the trace to evaluate against
     * @return true if the trace matches all filters, false otherwise
     */
    public boolean matchesAllFilters(@NonNull List<TraceFilter> filters, @NonNull Trace trace) {
        if (filters.isEmpty()) {
            return true; // Empty filter list means all traces match
        }

        return filters.stream().allMatch(filter -> matchesFilter(filter, trace));
    }

    /**
     * Evaluates whether a trace matches a single filter.
     *
     * @param filter the filter to evaluate
     * @param trace the trace to evaluate against
     * @return true if the trace matches the filter, false otherwise
     */
    public boolean matchesFilter(@NonNull TraceFilter filter, @NonNull Trace trace) {
        try {
            TraceField traceField = (TraceField) filter.field();
            Object fieldValue = extractFieldValue(traceField, filter.key(), trace);

            return evaluateOperator(filter.operator(), fieldValue, filter.value());
        } catch (Exception e) {
            log.warn("Error evaluating filter '{}' against trace '{}': '{}'", filter, trace.id(), e.getMessage());
            return false; // If we can't evaluate the filter, consider it a non-match
        }
    }

    /**
     * Extracts the value of a field from a trace object.
     */
    private Object extractFieldValue(TraceField field, String key, Trace trace) {
        return switch (field) {
            case ID -> trace.id();
            case NAME -> trace.name();
            case START_TIME -> trace.startTime();
            case END_TIME -> trace.endTime();
            case INPUT -> extractStringFromJson(trace.input());
            case OUTPUT -> extractStringFromJson(trace.output());
            case INPUT_JSON -> trace.input();
            case OUTPUT_JSON -> trace.output();
            case METADATA -> key != null ? extractNestedValue(trace.metadata(), key) : trace.metadata();
            case TAGS -> trace.tags();
            case TOTAL_ESTIMATED_COST -> trace.totalEstimatedCost();
            case USAGE_COMPLETION_TOKENS -> extractUsageValue(trace.usage(), "completion_tokens");
            case USAGE_PROMPT_TOKENS -> extractUsageValue(trace.usage(), "prompt_tokens");
            case USAGE_TOTAL_TOKENS -> extractUsageValue(trace.usage(), "total_tokens");
            case FEEDBACK_SCORES ->
                key != null ? extractFeedbackScore(trace.feedbackScores(), key) : trace.feedbackScores();
            case DURATION -> calculateDuration(trace.startTime(), trace.endTime());
            case THREAD_ID -> trace.threadId();
            default -> {
                log.warn("Unsupported trace field for filter evaluation: {}", field);
                yield null;
            }
        };
    }

    /**
     * Extracts a string value from a JSON object/string.
     */
    private String extractStringFromJson(Object jsonValue) {
        if (jsonValue == null) {
            return null;
        }
        if (jsonValue instanceof String str) {
            return str;
        }
        try {
            return JsonUtils.getMapper().writeValueAsString(jsonValue);
        } catch (JsonProcessingException e) {
            log.warn("Failed to convert value to string: {}", e.getMessage());
            return jsonValue.toString();
        }
    }

    /**
     * Extracts a nested value from a JSON object using a key.
     */
    private Object extractNestedValue(Object jsonValue, String key) {
        if (ObjectUtils.anyNull(jsonValue, key)) {
            return null;
        }

        try {
            JsonNode node;
            if (jsonValue instanceof String str) {
                node = JsonUtils.getMapper().readTree(str);
            } else {
                node = JsonUtils.getMapper().valueToTree(jsonValue);
            }

            JsonNode valueNode = node.get(key);
            if (valueNode == null) {
                return null;
            }

            if (valueNode.isTextual()) {
                return valueNode.textValue();
            } else if (valueNode.isNumber()) {
                return valueNode.numberValue();
            } else {
                return JsonUtils.getMapper().treeToValue(valueNode, Object.class);
            }
        } catch (Exception e) {
            log.warn("Failed to extract nested value with key '{}': {}", key, e.getMessage());
            return null;
        }
    }

    /**
     * Extracts a usage value from the usage map.
     */
    private Number extractUsageValue(Map<String, Long> usage, String key) {
        if (usage == null || key == null) {
            return null;
        }
        return usage.get(key);
    }

    /**
     * Extracts a feedback score value by name.
     */
    private Number extractFeedbackScore(List<FeedbackScore> feedbackScores, String scoreName) {
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
    private Number calculateDuration(Instant startTime, Instant endTime) {
        if (startTime == null || endTime == null) {
            return null;
        }
        return endTime.toEpochMilli() - startTime.toEpochMilli();
    }

    /**
     * Evaluates an operator against field value and filter value.
     */
    private boolean evaluateOperator(Operator operator, Object fieldValue, String filterValue) {
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
    private boolean evaluateDateTimeOperator(Operator operator, Instant fieldValue, String filterValue) {
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

    private boolean evaluateEquals(Object fieldValue, String filterValue) {
        if (fieldValue == null) {
            return filterValue == null || filterValue.isEmpty();
        }
        return Objects.equals(fieldValue.toString(), filterValue);
    }

    private boolean evaluateContains(Object fieldValue, String filterValue) {
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

    private boolean evaluateGreaterThan(Object fieldValue, String filterValue) {
        int comparison = compareNumbers(fieldValue, filterValue);
        // If comparison failed (sentinel values), the filter should not match
        return comparison != Integer.MIN_VALUE && comparison != Integer.MAX_VALUE && comparison > 0;
    }

    private boolean evaluateGreaterThanEqual(Object fieldValue, String filterValue) {
        int comparison = compareNumbers(fieldValue, filterValue);
        // If comparison failed (sentinel values), the filter should not match
        return comparison != Integer.MIN_VALUE && comparison != Integer.MAX_VALUE && comparison >= 0;
    }

    private boolean evaluateLessThan(Object fieldValue, String filterValue) {
        int comparison = compareNumbers(fieldValue, filterValue);
        // If comparison failed (sentinel values), the filter should not match
        return comparison != Integer.MIN_VALUE && comparison != Integer.MAX_VALUE && comparison < 0;
    }

    private boolean evaluateLessThanEqual(Object fieldValue, String filterValue) {
        int comparison = compareNumbers(fieldValue, filterValue);
        // If comparison failed (sentinel values), the filter should not match
        return comparison != Integer.MIN_VALUE && comparison != Integer.MAX_VALUE && comparison <= 0;
    }

    private boolean evaluateIsEmpty(Object fieldValue) {
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
    private int compareNumbers(Object fieldValue, String filterValue) {
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
    private BigDecimal convertToNumber(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number num) {
            return new BigDecimal(num.toString());
        }

        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            log.warn("TraceFilterEvaluationService:convertToNumber() failed for value: {}", value);
            return null;
        }
    }
}
