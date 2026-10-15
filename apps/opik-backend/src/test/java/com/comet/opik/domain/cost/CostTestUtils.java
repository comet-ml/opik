package com.comet.opik.domain.cost;

import com.comet.opik.domain.token_usage.model.UsageContext;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;

import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Collectors;

/** What the backend stores for a span with this usage map: its usage as {@code SpanService} writes it, and its cost. */
@UtilityClass
public class CostTestUtils {

    public static Map<String, Integer> storedUsage(String provider, String model, Map<String, Integer> usage) {
        return usage == null ? null : CostService.toSpanUsage(provider, model, usage, UsageContext.NONE).toUsageMap();
    }

    /**
     * The usage stored for a span of a provider without a format of its own, such as PODAM's random providers, which
     * read usage in OpenAI's format.
     */
    public static Map<String, Integer> storedUsage(Map<String, Integer> usage) {
        return storedUsage("openai", null, usage);
    }

    /** A trace's usage when its one span logged this usage. */
    public static Map<String, Long> storedTraceUsage(Map<String, Integer> usage) {
        return storedUsage(usage).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().longValue()));
    }

    public static BigDecimal calculateCost(String model, String provider, Map<String, Integer> usage,
            JsonNode metadata) {
        return CostService.calculateCost(model, provider,
                CostService.toSpanUsage(provider, model, usage, UsageContext.NONE), metadata);
    }
}
