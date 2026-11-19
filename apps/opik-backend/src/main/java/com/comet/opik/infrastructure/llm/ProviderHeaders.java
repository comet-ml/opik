package com.comet.opik.infrastructure.llm;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** A provider's stored headers, as every client sends them. */
@Slf4j
@UtilityClass
public class ProviderHeaders {

    /**
     * The headers that can be sent, by name in any case. Saving refuses the others, but a row stored before that may
     * still hold them: a blank name or a missing value is left out, and of names differing only in case, the one
     * sorting last wins.
     */
    public static Map<String, String> normalized(Map<String, String> headers) {
        if (MapUtils.isEmpty(headers)) {
            return Map.of();
        }
        var normalized = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        headers.entrySet().stream()
                .filter(header -> {
                    if (StringUtils.isBlank(header.getKey()) || header.getValue() == null) {
                        // the name only: a value can carry a credential
                        log.warn("Leaving out a provider header without a name or a value, header '{}'",
                                header.getKey());
                        return false;
                    }
                    return true;
                })
                .sorted(Map.Entry.comparingByKey())
                .forEach(header -> normalized.put(header.getKey(), header.getValue()));
        return Collections.unmodifiableMap(normalized);
    }
}
