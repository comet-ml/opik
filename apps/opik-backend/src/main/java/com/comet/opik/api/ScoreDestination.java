package com.comet.opik.api;

/**
 * Determines which storage table a feedback score is routed to.
 * <p>
 * Regular online scoring writes to the {@code feedback_scores} table,
 * while test suite assertions are written to the {@code assertion_results} table.
 */
public enum ScoreDestination {
    FEEDBACK_SCORES,
    ASSERTION_RESULTS;

    private static final String SUITE_ASSERTION_CATEGORY = "suite_assertion";

    public static ScoreDestination fromCategoryName(String categoryName) {
        return SUITE_ASSERTION_CATEGORY.equals(categoryName)
                ? ASSERTION_RESULTS
                : FEEDBACK_SCORES;
    }
}
