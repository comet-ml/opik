package com.comet.opik.domain.evaluators;

import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceThreadField;
import com.comet.opik.api.filter.TraceThreadFilter;
import com.comet.opik.domain.threads.TraceThreadModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static com.comet.opik.api.filter.Operator.*;
import static com.comet.opik.api.filter.TraceThreadField.*;

/**
 * Service for evaluating filters against TraceThreadModel objects in memory.
 * This service is used by online evaluation rules to determine if a thread
 * matches the criteria defined in an automation rule's filters.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class TraceThreadFilterEvaluationService {

    private final @NonNull ObjectMapper objectMapper;

    /**
     * Evaluates whether a thread matches all the provided filters.
     * All filters must match for the thread to be considered a match (AND logic).
     *
     * @param filters the list of filters to evaluate
     * @param thread the thread to evaluate against
     * @return true if the thread matches all filters, false otherwise
     */
    public boolean matchesAllFilters(@NonNull List<TraceThreadFilter> filters, @NonNull TraceThreadModel thread) {
        if (filters.isEmpty()) {
            return true; // Empty filter list means all threads match
        }

        return filters.stream().allMatch(filter -> matchesFilter(filter, thread));
    }

    /**
     * Evaluates whether a thread matches a single filter.
     *
     * @param filter the filter to evaluate
     * @param thread the thread to evaluate against
     * @return true if the thread matches the filter, false otherwise
     */
    public boolean matchesFilter(@NonNull TraceThreadFilter filter, @NonNull TraceThreadModel thread) {
        try {
            TraceThreadField threadField = (TraceThreadField) filter.field();
            Object fieldValue = extractFieldValue(threadField, filter.key(), thread);
            return evaluateOperator(filter.operator(), fieldValue, filter.value());
        } catch (Exception e) {
            log.warn("Error evaluating filter '{}' against thread '{}': '{}'", filter, thread.id(), e.getMessage());
            return false; // If we can't evaluate the filter, consider it a non-match
        }
    }

    /**
     * Extracts the value of a field from a thread object.
     */
    private Object extractFieldValue(TraceThreadField field, String key, TraceThreadModel thread) {
        return switch (field) {
            case ID -> thread.id();
            case CREATED_AT -> thread.createdAt();
            case LAST_UPDATED_AT -> thread.lastUpdatedAt();
            case START_TIME -> thread.createdAt(); // Threads use createdAt as start time
            case END_TIME -> thread.lastUpdatedAt(); // Threads use lastUpdatedAt as end time
            case STATUS -> thread.status();
            case TAGS -> thread.tags();
            case DURATION -> calculateDuration(thread.createdAt(), thread.lastUpdatedAt());
            case FEEDBACK_SCORES -> null; // TODO: Add feedback scores support for threads
            case FIRST_MESSAGE -> null; // TODO: Add first message support
            case LAST_MESSAGE -> null; // TODO: Add last message support
            case NUMBER_OF_MESSAGES -> null; // TODO: Add number of messages support
            default -> {
                log.warn("Unsupported thread field for filter evaluation: {}", field);
                yield null;
            }
        };
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
        return comparison != Integer.MIN_VALUE && comparison != Integer.MAX_VALUE && comparison > 0;
    }

    private boolean evaluateGreaterThanEqual(Object fieldValue, String filterValue) {
        int comparison = compareNumbers(fieldValue, filterValue);
        return comparison != Integer.MIN_VALUE && comparison != Integer.MAX_VALUE && comparison >= 0;
    }

    private boolean evaluateLessThan(Object fieldValue, String filterValue) {
        int comparison = compareNumbers(fieldValue, filterValue);
        return comparison != Integer.MIN_VALUE && comparison != Integer.MAX_VALUE && comparison < 0;
    }

    private boolean evaluateLessThanEqual(Object fieldValue, String filterValue) {
        int comparison = compareNumbers(fieldValue, filterValue);
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
                return Integer.MIN_VALUE;
            }

            return fieldNumber.compareTo(filterNumber);
        } catch (NumberFormatException e) {
            log.warn("Failed to compare values as numbers: {} vs {}", fieldValue, filterValue);
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
            log.warn("TraceThreadFilterEvaluationService:convertToNumber() failed for value: {}", value);
            return null;
        }
    }
}