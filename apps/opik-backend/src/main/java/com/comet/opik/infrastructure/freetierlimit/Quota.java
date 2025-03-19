package com.comet.opik.infrastructure.freetierlimit;

import jakarta.validation.constraints.NotNull;
import lombok.Builder;

@Builder
public record Quota(int limit, int used, @NotNull QuotaType type) {
    public enum QuotaType {
        SPAN_COUNT
    }
}
