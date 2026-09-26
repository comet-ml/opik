package com.comet.opik.systemmetrics;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record SystemMetricBatch(
        @NotEmpty @Size(max = 5_000) List<@Valid SystemMetricPoint> metrics) {
}
