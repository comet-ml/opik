package com.comet.opik.domain.ailabels;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.NonNull;

/**
 * The renderer's result for one trace: a budget-fitted state, or a trace that stays over budget after every step and
 * is never submitted.
 */
public sealed interface RenderOutcome {

    TraceRef trace();

    record Rendered(@NonNull TraceRef trace, @NonNull JsonNode state, int bytes, @NonNull String compactionLevel)
            implements
                RenderOutcome {
    }

    record OverBudget(@NonNull TraceRef trace, int bytes) implements RenderOutcome {
    }
}
