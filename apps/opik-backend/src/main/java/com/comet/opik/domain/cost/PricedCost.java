package com.comet.opik.domain.cost;

import lombok.Builder;
import lombok.NonNull;

import java.math.BigDecimal;

@Builder(toBuilder = true)
public record PricedCost(@NonNull BigDecimal cost, @NonNull CostSource source) {
}
