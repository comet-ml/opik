package com.comet.opik.utils;

import lombok.experimental.UtilityClass;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Helpers for span/trace usage (token-count) maps.
 */
@UtilityClass
public class UsageUtils {

    /**
     * Returns a copy of the usage map with null-valued entries removed.
     * <p>
     * Null token counts must never reach ClickHouse: the {@code Map(String, Int64)} CAST in the span
     * insert/update queries rejects null values (CANNOT_CONVERT_TYPE, code 70), and the cost
     * calculators read usage via {@code getOrDefault(key, 0)}, which returns null (not the default)
     * for a key present with a null value and then NPEs unboxing it in {@code BigDecimal.valueOf}.
     * A null or empty input map yields an empty map.
     */
    public Map<String, Integer> sanitizeUsage(@Nullable Map<String, Integer> usage) {
        if (usage == null || usage.isEmpty()) {
            return Map.of();
        }
        var sanitized = new HashMap<String, Integer>(usage.size());
        usage.forEach((key, value) -> {
            if (value != null) {
                sanitized.put(key, value);
            }
        });
        return sanitized;
    }
}
