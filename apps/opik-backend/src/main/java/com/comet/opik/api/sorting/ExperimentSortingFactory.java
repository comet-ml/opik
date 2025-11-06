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

    /**
     * Parsed parts of a feedback score field.
     *
     * @param name the feedback score name (e.g., "hallucination")
     * @param type the metric type (e.g., "f1_score"), or null if not present
     */
    public record ScoreFieldParts(String name, String type) {
    }

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
        // Feedback score fields use field mappings with literal SQL strings, not dynamic keys
        // Set bindKeyParam to null so handleNullDirection() returns empty (no tuple wrapping needed)
        return sorting.stream()
                .map(this::ensureFeedbackScoresField)
                .toList();
    }

    private SortingField ensureFeedbackScoresField(SortingField sortingField) {
        String field = sortingField.field();

        // Feedback score fields should NOT be treated as dynamic
        // because they use field mappings with literal SQL strings (coalesce expressions) in ExperimentDAO
        if (field.startsWith(FEEDBACK_SCORES_PREFIX)) {
            return sortingField.toBuilder()
                    .bindKeyParam(null)
                    .build();
        }

        return sortingField;
    }

    /**
     * Parses a feedback score field into its name and type components.
     *
     * @param field the field to parse (e.g., "feedback_scores.hallucination.f1_score")
     * @return the parsed parts, or null if the field is not a feedback_scores field
     */
    public static ScoreFieldParts parseScoreField(String field) {
        if (!field.startsWith(FEEDBACK_SCORES_PREFIX)) {
            return null;
        }

        String remainder = field.substring(FEEDBACK_SCORES_PREFIX.length());
        int secondDot = remainder.indexOf('.');

        if (secondDot > 0 && secondDot < remainder.length() - 1) {
            // Format: feedback_scores.name.type
            return new ScoreFieldParts(remainder.substring(0, secondDot), remainder.substring(secondDot + 1));
        } else {
            // Format: feedback_scores.name (legacy or avg scores)
            return new ScoreFieldParts(remainder, null);
        }
    }
}
