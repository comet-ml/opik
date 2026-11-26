package com.comet.opik.api.modelprice;

import com.comet.opik.domain.cost.CustomModelPriceValidator;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;

@Builder(toBuilder = true)
public record CustomModelPriceProblems(
        @NonNull List<CustomModelPriceValidator.Problem> errors,
        @NonNull List<CustomModelPriceValidator.Problem> warnings) {
}
