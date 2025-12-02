package com.comet.opik.domain.evaluators;

import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.Span;
import com.comet.opik.api.filter.Field;
import com.comet.opik.api.filter.SpanField;
import com.comet.opik.api.filter.SpanFilter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Service for evaluating filters against Span objects in memory.
 * This is used by the online scoring system to filter spans before applying automation rules.
 */
@Singleton
@Slf4j
public class SpanFilterEvaluationService extends FilterEvaluationServiceBase<Span> {

    @Inject
    public SpanFilterEvaluationService() {
        super(Span.class);
    }

    /**
     * Evaluates whether a span matches all the provided filters.
     * All filters must match for the span to be considered a match (AND logic).
     *
     * @param filters the list of filters to evaluate
     * @param span the span to evaluate against
     * @return true if the span matches all filters, false otherwise
     */
    public boolean matchesAllFilters(@NonNull List<SpanFilter> filters, @NonNull Span span) {
        return super.matchesAllFilters(filters, span);
    }

    /**
     * Evaluates whether a span matches a single filter.
     *
     * @param filter the filter to evaluate
     * @param span the span to evaluate against
     * @return true if the span matches the filter, false otherwise
     */
    public boolean matchesFilter(@NonNull SpanFilter filter, @NonNull Span span) {
        return super.matchesFilter(filter, span);
    }

    @Override
    protected String getEntityId(Span entity) {
        return entity.id().toString();
    }

    @Override
    protected Object extractFieldValue(Field field, String key, Span span) {
        SpanField spanField = (SpanField) field;
        return switch (spanField) {
            case ID -> span.id();
            case NAME -> span.name();
            case TYPE -> span.type() != null ? span.type().toString() : null;
            case START_TIME -> span.startTime();
            case END_TIME -> span.endTime();
            case INPUT -> extractStringFromJson(span.input());
            case OUTPUT -> extractStringFromJson(span.output());
            case INPUT_JSON -> key != null ? extractNestedValue(span.input(), key) : span.input();
            case OUTPUT_JSON -> key != null ? extractNestedValue(span.output(), key) : span.output();
            case METADATA -> key != null ? extractNestedValue(span.metadata(), key) : span.metadata();
            case MODEL -> span.model();
            case PROVIDER -> span.provider();
            case TAGS -> span.tags();
            case TOTAL_ESTIMATED_COST -> span.totalEstimatedCost();
            case USAGE_COMPLETION_TOKENS -> extractUsageValue(span.usage(), "completion_tokens");
            case USAGE_PROMPT_TOKENS -> extractUsageValue(span.usage(), "prompt_tokens");
            case USAGE_TOTAL_TOKENS -> extractUsageValue(span.usage(), "total_tokens");
            case FEEDBACK_SCORES ->
                key != null ? extractFeedbackScore(span.feedbackScores(), key) : span.feedbackScores();
            case DURATION ->
                span.duration() != null ? span.duration() : calculateDuration(span.startTime(), span.endTime());
            case ERROR_INFO -> key != null ? extractErrorInfoField(span.errorInfo(), key) : span.errorInfo();
            case CUSTOM -> extractCustomFieldValue(key, span);
            default -> {
                log.warn("Unsupported span field for filter evaluation: {}", spanField);
                yield null;
            }
        };
    }

    /**
     * Extracts value from a custom field filter.
     * Custom filters have a key in format "input.path" or "output.path" where path is the JSON path.
     * For example: key="input.message" extracts span.input().message
     */
    private Object extractCustomFieldValue(String key, Span span) {
        if (key == null || key.isEmpty()) {
            return null;
        }

        // Parse key format: "input.path" or "output.path"
        int dotIndex = key.indexOf('.');
        if (dotIndex <= 0) {
            log.warn("Invalid custom filter key format: '{}'. Expected format: 'input.path' or 'output.path'", key);
            return null;
        }

        String baseField = key.substring(0, dotIndex);
        String nestedKey = key.substring(dotIndex + 1);

        return switch (baseField) {
            case "input" -> extractNestedValue(span.input(), nestedKey);
            case "output" -> extractNestedValue(span.output(), nestedKey);
            default -> {
                log.warn("Unsupported base field in custom filter key: '{}'. Supported: 'input', 'output'", baseField);
                yield null;
            }
        };
    }

    /**
     * Extracts a field value from ErrorInfo object.
     * Supports: exceptionType, message, traceback
     */
    private Object extractErrorInfoField(ErrorInfo errorInfo, String key) {
        if (errorInfo == null || key == null) {
            return null;
        }
        return switch (key.toLowerCase()) {
            case "exceptiontype", "exception_type" -> errorInfo.exceptionType();
            case "message" -> errorInfo.message();
            case "traceback" -> errorInfo.traceback();
            default -> {
                log.warn("Unknown ErrorInfo field key: '{}'. Supported keys: exceptionType, message, traceback", key);
                yield null;
            }
        };
    }

}
