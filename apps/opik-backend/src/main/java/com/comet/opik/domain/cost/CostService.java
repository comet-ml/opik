package com.comet.opik.domain.cost;

import com.comet.opik.domain.token_usage.SpanUsageFactory;
import com.comet.opik.domain.token_usage.model.SpanUsage;
import com.fasterxml.jackson.databind.JsonNode;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import lombok.extern.slf4j.Slf4j;

import javax.annotation.Nullable;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Optional;

@Slf4j
public class CostService {
    // Usage priced although it contradicts its provider's format: shapes no format reads yet, or numbers that disagree.
    private static final LongCounter USAGE_WARNINGS = GlobalOpenTelemetry.get().getMeter("opik.cost")
            .counterBuilder("opik.cost.usage.warnings")
            .setDescription("Span usage priced despite not matching its provider's usage format (tagged by type)")
            .build();
    private static final AttributeKey<String> WARNING_TYPE_KEY = AttributeKey.stringKey("type");
    private static final ModelPrice DEFAULT_COST = ModelPrice.empty();

    /** Prices the usage as its provider's format reads it; the usage itself isn't changed. */
    public static BigDecimal calculateCost(@Nullable String modelName, @Nullable String provider,
            @Nullable Map<String, Integer> usage, @Nullable JsonNode metadata) {
        BigDecimal estimatedCost = estimateCost(modelName, provider, usage);
        return estimatedCost.compareTo(BigDecimal.ZERO) > 0 ? estimatedCost : getCostFromMetadata(metadata);
    }

    // Pricing runs while spans are ingested, so it never fails the caller: an error while pricing gives no estimate.
    private static BigDecimal estimateCost(@Nullable String modelName, @Nullable String provider,
            @Nullable Map<String, Integer> usage) {
        try {
            ModelPrice modelPrice = ModelPriceResolver.resolve(modelName, provider)
                    .map(ModelPriceResolver.PriceMatch::price)
                    .orElse(DEFAULT_COST);
            SpanUsage spanUsage = SpanUsageFactory.create(ProviderRegistry.resolve(provider, modelName).orElse(null),
                    usage);
            spanUsage.warnings().forEach(warning -> USAGE_WARNINGS.add(1,
                    Attributes.of(WARNING_TYPE_KEY, warning.type().name())));
            return modelPrice.calculator().apply(modelPrice, spanUsage);
        } catch (RuntimeException exception) {
            log.warn("Failed to estimate the cost of model '{}', provider '{}'", modelName, provider, exception);
            return BigDecimal.ZERO;
        }
    }

    public static BigDecimal getCostFromMetadata(JsonNode metadata) {
        return Optional.ofNullable(metadata)
                .map(md -> md.get("cost"))
                .map(cost -> Optional.ofNullable(cost.get("currency"))
                        .map(JsonNode::asText)
                        .filter("USD"::equals)
                        .flatMap(currency -> Optional.ofNullable(cost.get("total_cost")))
                        .map(JsonNode::decimalValue)
                        .orElse(BigDecimal.ZERO))
                .orElse(BigDecimal.ZERO);
    }
}
