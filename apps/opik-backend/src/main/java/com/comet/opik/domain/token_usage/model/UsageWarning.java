package com.comet.opik.domain.token_usage.model;

import lombok.NonNull;

import java.util.Set;
import java.util.TreeSet;

/**
 * Something the caller should count in a metric. The package doesn't emit metrics or log itself, so it stays free of
 * the rest of the backend.
 */
public record UsageWarning(@NonNull Type type, @NonNull String detail) {

    public enum Type {
        /** The provider has a format, but none of its input variants matched the usage keys. */
        NO_MATCHING_VARIANT,
        /** An input that should include the cache is smaller than it, so the numbers contradict the variant. */
        CACHE_EXCEEDS_INPUT
    }

    public static UsageWarning noMatchingVariant(@NonNull String provider, @NonNull Set<String> keys) {
        return new UsageWarning(Type.NO_MATCHING_VARIANT, "provider '%s', keys %s".formatted(provider,
                new TreeSet<>(keys)));
    }

    public static UsageWarning cacheExceedsInput(@NonNull String provider, @NonNull String variant, long input,
            long cache) {
        return new UsageWarning(Type.CACHE_EXCEEDS_INPUT, "provider '%s', variant '%s': input %d, cache %d"
                .formatted(provider, variant, input, cache));
    }
}
