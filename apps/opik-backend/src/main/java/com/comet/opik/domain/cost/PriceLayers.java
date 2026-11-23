package com.comet.opik.domain.cost;

import lombok.Getter;
import lombok.NonNull;

import javax.annotation.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class PriceLayers {

    public static final PriceLayers BUILT_IN_ONLY = new PriceLayers(null, 0, List.of());

    @Getter
    private final @Nullable String organizationId;
    @Getter
    private final long listVersion;
    private final Map<RowKey, CustomModelPrice> rowsByKey;

    private PriceLayers(@Nullable String organizationId, long listVersion, List<CustomModelPrice> rows) {
        this.organizationId = organizationId;
        this.listVersion = listVersion;
        this.rowsByKey = rows.stream()
                .collect(Collectors.toUnmodifiableMap(row -> RowKey.of(row.modelName(), row.provider()),
                        Function.identity(), (first, duplicate) -> first));
    }

    public static PriceLayers of(@NonNull String organizationId, long listVersion,
            @NonNull List<CustomModelPrice> rows) {
        return new PriceLayers(organizationId, listVersion, rows);
    }

    boolean hasRows() {
        return !rowsByKey.isEmpty();
    }

    @Nullable CustomModelPrice find(@NonNull String modelName, @NonNull String provider) {
        return rowsByKey.get(RowKey.of(modelName, provider));
    }

    private record RowKey(String modelName, String provider) {
        static RowKey of(String modelName, String provider) {
            return new RowKey(modelName.toLowerCase(Locale.ROOT), provider.toLowerCase(Locale.ROOT));
        }
    }
}
