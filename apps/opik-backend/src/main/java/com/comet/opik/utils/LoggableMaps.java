package com.comet.opik.utils;

import jakarta.annotation.Nullable;
import lombok.experimental.UtilityClass;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * The form of a configured map that may be logged, for a {@code toString}: header and configuration values can carry
 * credentials, so only the keys are.
 */
@UtilityClass
public class LoggableMaps {

    /** The sorted keys of {@code map}, or null when it is null, so an unset map still reads as unset. */
    public static @Nullable List<String> keys(@Nullable Map<String, ?> map) {
        return map == null
                ? null
                : map.keySet().stream().sorted(Comparator.nullsFirst(Comparator.naturalOrder())).toList();
    }
}
