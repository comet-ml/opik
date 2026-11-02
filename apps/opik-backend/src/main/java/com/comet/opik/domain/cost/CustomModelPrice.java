package com.comet.opik.domain.cost;

import lombok.Builder;
import lombok.NonNull;

import javax.annotation.Nullable;

import java.math.BigDecimal;

// A null price inherits the built-in rate, while 0 makes it free. An alias provider of ANY_PROVIDER means the span's own.
@Builder(toBuilder = true)
public record CustomModelPrice(
        @NonNull String modelName,
        @NonNull String provider,
        @NonNull Kind kind,
        @Nullable BigDecimal inputPerMillion,
        @Nullable BigDecimal outputPerMillion,
        @Nullable BigDecimal cacheReadPerMillion,
        @Nullable BigDecimal cacheWritePerMillion,
        @Nullable String aliasModelName,
        @Nullable String aliasProvider) {

    public static final String ANY_PROVIDER = "";

    public enum Kind {
        FIXED,
        ALIAS
    }
}
