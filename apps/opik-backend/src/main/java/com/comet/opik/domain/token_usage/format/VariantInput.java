package com.comet.opik.domain.token_usage.format;

import com.comet.opik.domain.token_usage.model.UsageWarning;
import lombok.NonNull;

import java.util.List;

record VariantInput(
        @NonNull String provider,
        @NonNull String variant,
        @NonNull UsageKeys keys,
        @NonNull List<UsageWarning> warnings) {

    /**
     * Removes the cache from an input that includes it. An input smaller than its own cache can't include it, so it's
     * reported and taken as already excluding the cache.
     */
    long excludingCache(long inputIncludingCache, long cacheTokens) {
        if (inputIncludingCache < cacheTokens) {
            warnings.add(UsageWarning.cacheExceedsInput(provider, variant, inputIncludingCache, cacheTokens));
            return inputIncludingCache;
        }
        return inputIncludingCache - cacheTokens;
    }

    /** Reports an input that includes the cache but is smaller than it. The provider's own values are kept. */
    void checkCacheWithinInput(long inputIncludingCache, long cacheTokens) {
        if (inputIncludingCache < cacheTokens) {
            warnings.add(UsageWarning.cacheExceedsInput(provider, variant, inputIncludingCache, cacheTokens));
        }
    }
}
