package com.comet.opik.podam.manufacturer;

import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.api.PodamUtils;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.math.BigDecimal;
import java.math.RoundingMode;

import static com.comet.opik.api.FeedbackDefinition.NumericalFeedbackDefinition.NumericalFeedbackDetail;
import static com.comet.opik.utils.ValidationUtils.SCALE;

public class NumericalFeedbackDetailTypeManufacturer extends AbstractTypeManufacturer<NumericalFeedbackDetail> {

    @Override
    public NumericalFeedbackDetail getType(DataProviderStrategy dataProviderStrategy,
            AttributeMetadata attributeMetadata,
            ManufacturingContext manufacturingContext) {

        var min = PodamUtils.getDoubleInRange(0, 100);
        var max = PodamUtils.getDoubleInRange(min, 1000);

        return new NumericalFeedbackDetail(BigDecimal.valueOf(max).setScale(SCALE, RoundingMode.HALF_EVEN),
                BigDecimal.valueOf(min).setScale(SCALE, RoundingMode.HALF_EVEN));
    }
}
