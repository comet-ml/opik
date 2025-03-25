package com.comet.opik.infrastructure.usagelimit;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record Quota(int limit, int used, @NotNull QuotaType type) {
    public enum QuotaType {
        OPIK_SPAN_COUNT
    }
}
