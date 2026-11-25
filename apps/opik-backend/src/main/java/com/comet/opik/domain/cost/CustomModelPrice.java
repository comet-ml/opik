package com.comet.opik.domain.cost;

import com.comet.opik.infrastructure.db.HasValue;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import javax.annotation.Nullable;

import java.math.BigDecimal;
import java.util.Arrays;

// A null price inherits the built-in rate, while 0 makes it free. Not for the cache rates: built-in rows store a
// missing cache price as 0, so the calculator bills cached tokens at the input rate when a cache rate isn't above 0.
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

    /** As {@code provider}: the row prices the model under every provider. */
    public static final String ANY_PROVIDER = "";
    /** As {@code aliasProvider}: the target is looked up under the span's own provider. */
    public static final String SPAN_PROVIDER = "";

    @Getter
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public enum Kind implements HasValue {
        FIXED("fixed"),
        ALIAS("alias");

        @JsonValue
        private final String value;

        @JsonCreator
        public static Kind fromString(String value) {
            return Arrays.stream(values())
                    .filter(kind -> kind.value.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown model price kind '%s'".formatted(value)));
        }
    }
}
