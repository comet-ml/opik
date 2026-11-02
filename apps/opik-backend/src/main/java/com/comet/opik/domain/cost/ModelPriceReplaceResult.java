package com.comet.opik.domain.cost;

import lombok.NonNull;

import java.util.List;

/** A replace-all's outcome: the list version after it, whether anything was written, and what changed. */
public record ModelPriceReplaceResult(
        long version,
        boolean saved,
        @NonNull ModelPriceDiff diff,
        @NonNull List<CustomModelPriceValidator.Problem> warnings) {

    ModelPriceReplaceResult withWarnings(@NonNull List<CustomModelPriceValidator.Problem> warnings) {
        return new ModelPriceReplaceResult(version, saved, diff, warnings);
    }
}
