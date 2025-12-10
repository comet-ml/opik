package com.comet.opik.api;

import lombok.Builder;

import java.math.BigDecimal;

@Builder(toBuilder = true)
public record PercentageValues(BigDecimal p50, BigDecimal p90, BigDecimal p99) {
}
