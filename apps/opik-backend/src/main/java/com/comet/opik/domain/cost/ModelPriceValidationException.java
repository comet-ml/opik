package com.comet.opik.domain.cost;

import lombok.Getter;
import lombok.NonNull;

/** Rows a replace was refused for, with every error and warning so the caller can show them all at once. */
@Getter
public class ModelPriceValidationException extends RuntimeException {

    private final @NonNull CustomModelPriceValidator.Result result;

    public ModelPriceValidationException(@NonNull CustomModelPriceValidator.Result result) {
        super("%d model price rows are invalid".formatted(result.errors().size()));
        this.result = result;
    }
}
