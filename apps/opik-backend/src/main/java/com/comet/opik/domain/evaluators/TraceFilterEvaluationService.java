package com.comet.opik.domain.evaluators;

import com.comet.opik.api.Trace;
import com.comet.opik.api.filter.Field;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Service for evaluating filters against Trace objects in memory.
 * This is used by the online scoring system to filter traces before applying automation rules.
 */
@Slf4j
@Singleton
public class TraceFilterEvaluationService extends FilterEvaluationServiceBase<Trace> {

    @Inject
    public TraceFilterEvaluationService() {
        super(Trace.class);
    }

    /**
     * Evaluates whether a trace matches all the provided filters.
     * All filters must match for the trace to be considered a match (AND logic).
     *
     * @param filters the list of filters to evaluate
     * @param trace the trace to evaluate against
     * @return true if the trace matches all filters, false otherwise
     */
    public boolean matchesAllFilters(@NonNull List<TraceFilter> filters, @NonNull Trace trace) {
        return super.matchesAllFilters(filters, trace);
    }

    /**
     * Evaluates whether a trace matches a single filter.
     *
     * @param filter the filter to evaluate
     * @param trace the trace to evaluate against
     * @return true if the trace matches the filter, false otherwise
     */
    public boolean matchesFilter(@NonNull TraceFilter filter, @NonNull Trace trace) {
        return super.matchesFilter(filter, trace);
    }

    @Override
    protected String getEntityId(Trace entity) {
        return entity.id().toString();
    }

    @Override
    protected Object extractFieldValue(Field field, String key, Trace trace) {
        TraceField traceField = (TraceField) field;
        return switch (traceField) {
            case ID -> trace.id();
            case NAME -> trace.name();
            case START_TIME -> trace.startTime();
            case END_TIME -> trace.endTime();
            case INPUT -> extractStringFromJson(trace.input());
            case OUTPUT -> extractStringFromJson(trace.output());
            case INPUT_JSON -> key != null ? extractNestedValue(trace.input(), key) : trace.input();
            case OUTPUT_JSON -> key != null ? extractNestedValue(trace.output(), key) : trace.output();
            case METADATA -> key != null ? extractNestedValue(trace.metadata(), key) : trace.metadata();
            case TAGS -> trace.tags();
            case TOTAL_ESTIMATED_COST -> trace.totalEstimatedCost();
            case USAGE_COMPLETION_TOKENS -> extractUsageValue(trace.usage(), "completion_tokens");
            case USAGE_PROMPT_TOKENS -> extractUsageValue(trace.usage(), "prompt_tokens");
            case USAGE_TOTAL_TOKENS -> extractUsageValue(trace.usage(), "total_tokens");
            case FEEDBACK_SCORES ->
                key != null ? extractFeedbackScore(trace.feedbackScores(), key) : trace.feedbackScores();
            case DURATION -> calculateDuration(trace.startTime(), trace.endTime());
            case TTFT -> trace.ttft();
            case THREAD_ID -> trace.threadId();
            case CUSTOM -> extractCustomFieldValue(key, trace);
            default -> {
                log.warn("Unsupported trace field for filter evaluation: {}", traceField);
                yield null;
            }
        };
    }

    /**
     * Extracts value from a custom field filter.
     * Custom filters have a key in format "input.path" or "output.path" where path is the JSON path.
     * For example: key="input.message" extracts trace.input().message
     */
    private Object extractCustomFieldValue(String key, Trace trace) {
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
            case "input" -> extractNestedValue(trace.input(), nestedKey);
            case "output" -> extractNestedValue(trace.output(), nestedKey);
            default -> {
                log.warn("Unsupported base field in custom filter key: '{}'. Supported: 'input', 'output'", baseField);
                yield null;
            }
        };
    }
}
