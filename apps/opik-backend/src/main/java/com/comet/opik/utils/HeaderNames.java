package com.comet.opik.utils;

import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Map;

/**
 * The names of configured HTTP headers, for a {@code toString}: their values can carry credentials, so they are never
 * logged.
 */
@UtilityClass
public class HeaderNames {

    /** The sorted names of {@code headers}, or null when there are none configured. */
    public static List<String> of(Map<String, String> headers) {
        return headers == null ? null : headers.keySet().stream().sorted().toList();
    }
}
