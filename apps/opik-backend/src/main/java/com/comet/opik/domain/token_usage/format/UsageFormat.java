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

    default Optional<NativeUsage> normalize(@NonNull String provider, @NonNull OriginalUsage original,
            @NonNull List<UsageWarning> warnings) {
        var keys = UsageKeys.of(original);
        return inputVariants().stream()
                .filter(variant -> variant.matches().test(keys))
                .findFirst()
                .map(variant -> variant.toNative().apply(new VariantInput(provider, variant.name(), keys, warnings)));
    }
}
