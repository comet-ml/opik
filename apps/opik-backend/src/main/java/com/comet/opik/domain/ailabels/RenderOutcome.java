package com.comet.opik.domain.ailabels;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import lombok.NonNull;

/**
 * The renderer's result for one trace and one kind of task: a budget-fitted state, or a trace that stays over budget
 * after every step and is never submitted. {@link #withHistory()} says which tasks it is for: those whose prompt has
 * {@code {{history}}}, or the rest.
 */
public sealed interface RenderOutcome {

    TraceRef trace();

    boolean withHistory();

    /** {@code history} is the fitted earlier turns, oldest first, and null for the tasks without them. */
    record Rendered(@NonNull TraceRef trace, @NonNull JsonNode state, @Nullable JsonNode history, int bytes,
            @NonNull String compactionLevel) implements RenderOutcome {

        @Override
        public boolean withHistory() {
            return history != null;
        }
    }

    record OverBudget(@NonNull TraceRef trace, boolean withHistory, int bytes) implements RenderOutcome {
    }
}
