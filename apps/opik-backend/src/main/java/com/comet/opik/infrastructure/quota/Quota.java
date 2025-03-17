package com.comet.opik.infrastructure.quota;

import jakarta.validation.constraints.NotNull;

public record Quota(int limit, int used, @NotNull QuotaType type) {
    public enum QuotaType {
        SPAN_COUNT
    }
}
