package com.comet.opik.domain.cost;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/** A replace-all's outcome: the list version after it, whether anything was written, and what changed. */
@Builder(toBuilder = true)
public record ModelPriceReplaceResult(
        long version,
        boolean saved,
        @NonNull ModelPriceDiff diff,
        @NonNull List<CustomModelPriceValidator.Problem> warnings) {
}
