package com.comet.opik.domain.token_usage.format;

import com.comet.opik.domain.token_usage.model.UsageWarning;
import jakarta.annotation.Nullable;
import lombok.NonNull;

import java.util.List;

record VariantInput(
        @NonNull String provider,
        @NonNull String variant,
        @NonNull UsageKeys keys,
        @NonNull List<UsageWarning> warnings) {

    /** The input without the cache, for a variant whose input includes it unless the numbers say otherwise. */
    long excludingCache(long input, long cacheTokens, @Nullable Long outputTokens) {
        return cacheInsideInput(input, cacheTokens, outputTokens) ? input - cacheTokens : input;
    }

    /** The input with the cache in it, for a variant whose input includes it unless the numbers say otherwise. */
    long includingCache(long input, long cacheTokens, @Nullable Long outputTokens) {
        return cacheInsideInput(input, cacheTokens, outputTokens) ? input : input + cacheTokens;
    }

    /**
     * Clients disagree on whether an input reported next to its cache includes it, so the numbers decide when they
     * can. An input smaller than its cache can't include it, and is reported. Neither can one whose reported total only
     * adds up with the cache on top of the input and the output, when the output is known. Otherwise the variant's
     * own convention holds: the input includes the cache.
     */
    private boolean cacheInsideInput(long input, long cacheTokens, @Nullable Long outputTokens) {
        if (cacheTokens == 0) {
            return true;
        }
        if (input < cacheTokens) {
            warnings.add(UsageWarning.cacheExceedsInput(provider, variant, input, cacheTokens));
            return false;
        }
        Long total = keys.reportedTotal();
        return total == null || outputTokens == null || total != input + cacheTokens + outputTokens;
    }
}
