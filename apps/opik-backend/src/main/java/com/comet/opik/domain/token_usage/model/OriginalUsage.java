package com.comet.opik.domain.token_usage.model;

import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/**
 * A span's usage as the client logged it, never modified.
 *
 * @param keys      the provider's usage. When the client already converted it (any {@code original_usage.*} key),
 *                  these are those keys without the prefix; otherwise all the keys it sent.
 * @param outerKeys the keys sent next to {@code original_usage.*}, such as the SDK's own totals. Empty otherwise.
 * @param nested    whether the client sent {@code original_usage.*} keys.
 */
@Builder(toBuilder = true)
public record OriginalUsage(
        @NonNull Map<String, Integer> keys,
        @NonNull Map<String, Integer> outerKeys,
        boolean nested) {

    public static final String ORIGINAL_USAGE_PREFIX = "original_usage.";

    public static final OriginalUsage EMPTY = new OriginalUsage(Map.of(), Map.of(), false);

    // Copies keep the keys sorted: when two spellings normalize to the same name, the first one wins.
    public OriginalUsage {
        keys = Collections.unmodifiableMap(new TreeMap<>(keys));
        outerKeys = Collections.unmodifiableMap(new TreeMap<>(outerKeys));
    }

    /** The provider's usage with the keys the client sent next to it, which fill in the counts it lacks. */
    public OriginalUsage withOuterKeys() {
        var merged = new TreeMap<>(outerKeys);
        merged.putAll(keys);
        return new OriginalUsage(merged, Map.of(), false);
    }

    public static OriginalUsage from(@Nullable Map<String, Integer> usage) {
        if (usage == null || usage.isEmpty()) {
            return EMPTY;
        }
        var outerKeys = new TreeMap<String, Integer>();
        var keysByDepth = new TreeMap<Integer, Map<String, Integer>>();
        usage.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            // One substring at the end: stripping one prefix at a time copies the rest of the key every time.
            int depth = 0;
            int nameStart = 0;
            while (key.startsWith(ORIGINAL_USAGE_PREFIX, nameStart)) {
                nameStart += ORIGINAL_USAGE_PREFIX.length();
                depth++;
            }
            String name = key.substring(nameStart);
            if (name.isEmpty()) {
                return;
            }
            if (depth == 0) {
                outerKeys.put(key, value);
            } else {
                keysByDepth.computeIfAbsent(depth, unused -> new TreeMap<>()).put(name, value);
            }
        });
        if (keysByDepth.isEmpty()) {
            return new OriginalUsage(outerKeys, Map.of(), false);
        }
        // Usage that was already converted and then logged as provider usage again nests two or three levels deep.
        // Only the deepest level is the provider's own usage; the shallower ones hold the earlier conversion's totals.
        return new OriginalUsage(keysByDepth.lastEntry().getValue(), outerKeys, true);
    }
}
