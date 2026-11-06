package com.comet.opik.domain.cost;

import lombok.NonNull;

import java.util.List;

/** An organization's saved rows and the list version they make up; cached, so it holds plain data only. */
public record ModelPriceList(@NonNull String organizationId, long version, @NonNull List<CustomModelPrice> rows) {

    public PriceLayers toLayers() {
        return PriceLayers.of(organizationId, version, rows);
    }
}
