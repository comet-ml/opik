package com.comet.opik.utils;

import lombok.experimental.UtilityClass;

import javax.annotation.Nullable;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Helpers for span/trace usage (token-count) maps.
 */
@UtilityClass
public class UsageUtils {

    /**
     * Narrows a usage map read from the analytics DB to the {@code Integer} counts the API model carries.
     *
     * <p>The partitioned {@code spans} successor stores {@code usage} as {@code Map(String, Int64)}
     * (migration 000115) where the table it replaces stores {@code Map(String, Int32)}, so the driver hands back
     * {@code Long} values on one and {@code Integer} values on the other. {@code Span.usage} is a
     * {@code Map<String, Integer>}, and Jackson serializes the map against that declared value type by casting, so an
     * unnarrowed {@code Long} fails the whole response; the update path, which carries the stored counts forward into
     * an {@code Integer[]} bind, throws on the same value.
     *
     * <p>Narrowing cannot lose data: every ingestion boundary types the counts as {@code Integer}, so no stored count
     * exceeds {@code Int32}. Trace-level usage needs no equivalent, being a {@code sumMap} aggregate that ClickHouse
     * promotes to {@code Int64} on both tables and that the API already carries as {@code Map<String, Long>}.
     *
     * @return {@code null} when the input is {@code null}, so a column left out of the projection stays absent.
     */
    public Map<String, Integer> toIntegerUsage(@Nullable Map<?, ?> usage) {
        if (usage == null) {
            return null;
        }
        var narrowed = new LinkedHashMap<String, Integer>(usage.size());
        usage.forEach((key, value) -> narrowed.put((String) key, ((Number) value).intValue()));
        return narrowed;
    }

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
