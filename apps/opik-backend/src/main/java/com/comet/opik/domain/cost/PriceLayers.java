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

    /** Built-in prices only, with custom model prices switched off: spans are stamped exactly as before them. */
    public static final PriceLayers BUILT_IN_ONLY = new PriceLayers(Origin.DISABLED, null, 0, List.of());

    /** Built-in prices only, because the workspace's organization is unknown. */
    public static final PriceLayers NO_ORGANIZATION = new PriceLayers(Origin.NO_ORGANIZATION, null, 0, List.of());

    /** Built-in prices only, because the organization's prices could not be loaded. */
    public static final PriceLayers LOAD_FAILED = new PriceLayers(Origin.LOAD_FAILED, null, 0, List.of());

    enum Origin {
        DISABLED,
        NO_ORGANIZATION,
        LOAD_FAILED,
        ORGANIZATION
    }

    @Getter
    private final @NonNull Origin origin;
    @Getter
    private final @Nullable String organizationId;
    @Getter
    private final long listVersion;
    private final Map<RowKey, CustomModelPrice> rowsByKey;

    private PriceLayers(Origin origin, @Nullable String organizationId, long listVersion,
            List<CustomModelPrice> rows) {
        this.origin = origin;
        this.organizationId = organizationId;
        this.listVersion = listVersion;
        this.rowsByKey = rows.stream()
                .collect(Collectors.toUnmodifiableMap(row -> RowKey.of(row.modelName(), row.provider()),
                        Function.identity(), (first, duplicate) -> first));
    }

    public static PriceLayers of(@NonNull String organizationId, long listVersion,
            @NonNull List<CustomModelPrice> rows) {
        return new PriceLayers(Origin.ORGANIZATION, organizationId, listVersion, rows);
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
