package com.comet.opik.domain.token_usage.format;

import com.comet.opik.domain.token_usage.model.NativeUsage;
import lombok.NonNull;

import java.util.function.Function;
import java.util.function.Predicate;

/**
 * One known input variant of an official format: what it matches, and how its keys fill the format's native usage,
 * including whether its input includes the cache. Scoped to one format, so it never decides the provider.
 */
record InputVariant(
        @NonNull String name,
        @NonNull Predicate<UsageKeys> matches,
        @NonNull Function<VariantInput, NativeUsage> toNative) {
}
