package com.comet.opik.infrastructure.db;

import com.comet.opik.domain.cost.CustomModelPrice;

public class CustomModelPriceKindMapper extends AbstractEnumColumnMapper<CustomModelPrice.Kind> {
    public CustomModelPriceKindMapper() {
        super(CustomModelPrice.Kind::fromString, "model_price_kind");
    }
}
