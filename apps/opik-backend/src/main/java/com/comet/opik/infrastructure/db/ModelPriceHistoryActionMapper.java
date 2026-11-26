package com.comet.opik.infrastructure.db;

import com.comet.opik.domain.cost.ModelPriceHistoryAction;

public class ModelPriceHistoryActionMapper extends AbstractEnumColumnMapper<ModelPriceHistoryAction> {
    public ModelPriceHistoryActionMapper() {
        super(ModelPriceHistoryAction::fromString, "model_price_history_action");
    }
}
