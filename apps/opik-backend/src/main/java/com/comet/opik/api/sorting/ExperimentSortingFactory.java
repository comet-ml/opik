package com.comet.opik.api.sorting;

import lombok.NonNull;

import java.util.List;

import static com.comet.opik.api.sorting.SortableFields.CREATED_AT;
import static com.comet.opik.api.sorting.SortableFields.CREATED_BY;
import static com.comet.opik.api.sorting.SortableFields.DURATION_AGG;
import static com.comet.opik.api.sorting.SortableFields.FEEDBACK_SCORES;
import static com.comet.opik.api.sorting.SortableFields.ID;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_AT;
import static com.comet.opik.api.sorting.SortableFields.LAST_UPDATED_BY;
import static com.comet.opik.api.sorting.SortableFields.NAME;
import static com.comet.opik.api.sorting.SortableFields.TOTAL_ESTIMATED_COST;
import static com.comet.opik.api.sorting.SortableFields.TOTAL_ESTIMATED_COST_AVG;
import static com.comet.opik.api.sorting.SortableFields.TRACE_COUNT;

public class ExperimentSortingFactory extends SortingFactory {

    private static final String FEEDBACK_SCORES_PREFIX = "feedback_scores.";

    @Override
    public List<String> getSortableFields() {
        return List.of(
                ID,
                NAME,
                CREATED_AT,
                LAST_UPDATED_AT,
                CREATED_BY,
                LAST_UPDATED_BY,
                TRACE_COUNT,
                TOTAL_ESTIMATED_COST,
                TOTAL_ESTIMATED_COST_AVG,
                FEEDBACK_SCORES,
                DURATION_AGG);
    }

    @Override
    protected List<SortingField> processFields(@NonNull List<SortingField> sorting) {
        return sorting.stream()
                .map(this::ensureBindKeyParam)
                .toList();
    }

    private SortingField ensureBindKeyParam(SortingField sortingField) {
        String field = sortingField.field();

        // Check if it's a feedback_scores field with format: feedback_scores.name.type
        if (field.startsWith(FEEDBACK_SCORES_PREFIX)) {
            // Always ensure bindKeyParam is set for dynamic feedback_scores fields
            if (sortingField.bindKeyParam() == null) {
                return sortingField.toBuilder()
                        .bindKeyParam(java.util.UUID.randomUUID().toString().replace("-", ""))
                        .build();
            }
        }

        return sortingField;
    }

    /**
     * Extracts the feedback score name from a field like "feedback_scores.hallucination.f1_score"
     * Returns "hallucination"
     */
    public static String extractScoreName(String field) {
        if (!field.startsWith(FEEDBACK_SCORES_PREFIX)) {
            return null;
        }

        String remainder = field.substring(FEEDBACK_SCORES_PREFIX.length());
        int secondDot = remainder.indexOf('.');

        if (secondDot > 0) {
            // Format: feedback_scores.name.type
            return remainder.substring(0, secondDot);
        } else {
            // Format: feedback_scores.name (legacy or avg scores)
            return remainder;
        }
    }

    /**
     * Extracts the metric type from a field like "feedback_scores.hallucination.f1_score"
     * Returns "f1_score", or null if not present
     */
    public static String extractScoreType(String field) {
        if (!field.startsWith(FEEDBACK_SCORES_PREFIX)) {
            return null;
        }

        String remainder = field.substring(FEEDBACK_SCORES_PREFIX.length());
        int secondDot = remainder.indexOf('.');

        if (secondDot > 0 && secondDot < remainder.length() - 1) {
            // Format: feedback_scores.name.type
            return remainder.substring(secondDot + 1);
        }

        return null;
    }
}