package com.comet.opik.domain.cost;

import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;

import java.util.Locale;
import java.util.Optional;

/** A model and provider a span used, as {@code model_prices_used} stores them. */
public record UsedModel(@NonNull String modelName, @NonNull String provider) {

    static final int MAX_MODEL_NAME_LENGTH = 255;
    static final int MAX_PROVIDER_LENGTH = 150;

    /** Empty for a span with no model, or with names too long for any price row to match. */
    public static Optional<UsedModel> of(@Nullable String model, @Nullable String provider) {
        String trimmedModel = StringUtils.trimToNull(model);
        if (trimmedModel == null) {
            return Optional.empty();
        }
        String modelName = trimmedModel.toLowerCase(Locale.ROOT);
        String resolvedProvider = ProviderRegistry.resolve(StringUtils.trimToNull(provider), trimmedModel)
                .orElse(CustomModelPrice.ANY_PROVIDER);
        if (modelName.length() > MAX_MODEL_NAME_LENGTH || resolvedProvider.length() > MAX_PROVIDER_LENGTH) {
            return Optional.empty();
        }
        return Optional.of(new UsedModel(modelName, resolvedProvider));
    }
}
