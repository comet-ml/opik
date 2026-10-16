package com.comet.opik.domain.token_usage.format;

import com.comet.opik.domain.token_usage.model.NativeUsage;
import com.comet.opik.domain.token_usage.model.OriginalUsage;
import com.comet.opik.domain.token_usage.model.UsageWarning;
import lombok.NonNull;

import java.util.List;
import java.util.Optional;

/** A provider's official usage format, with the input variants it's known to receive, checked in order. */
interface UsageFormat {

    List<InputVariant> inputVariants();

    /**
     * The variant is picked from the provider's own keys. Its counts also come from the keys the client sent next to
     * them, such as the SDK's totals, for the ones the provider's keys lack.
     */
    default Optional<NativeUsage> normalize(@NonNull String provider, @NonNull OriginalUsage original,
            @NonNull List<UsageWarning> warnings) {
        var providerKeys = UsageKeys.of(original);
        var combinedKeys = original.nested() ? UsageKeys.withOuterKeys(original) : providerKeys;
        return inputVariants().stream()
                .filter(variant -> variant.matches().test(providerKeys))
                .findFirst()
                .map(variant -> variant.toNative()
                        .apply(new VariantInput(provider, variant.name(), combinedKeys, warnings)));
    }
}
