package com.comet.opik.api.resources.v1.events.tools;

import lombok.experimental.UtilityClass;

/**
 * Char-based token estimator: {@code len(text) // 4}. ±20% on English, which
 * is acceptable for tier <em>selection</em> — we have headroom in the
 * thresholds. Swap to a real per-model tokenizer if we ever expose token
 * counts to the model directly.
 */
@UtilityClass
final class Tokens {

    static int estimate(String text) {
        return text == null ? 0 : text.length() / 4;
    }
}