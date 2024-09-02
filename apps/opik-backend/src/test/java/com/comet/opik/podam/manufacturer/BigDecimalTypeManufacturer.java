package com.comet.opik.podam.manufacturer;

import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.api.PodamUtils;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

import static com.comet.opik.utils.ValidationUtils.MAX_FEEDBACK_SCORE_VALUE;
import static com.comet.opik.utils.ValidationUtils.MIN_FEEDBACK_SCORE_VALUE;
import static com.comet.opik.utils.ValidationUtils.SCALE;

public class BigDecimalTypeManufacturer extends AbstractTypeManufacturer<BigDecimal> {

    public static final BigDecimalTypeManufacturer INSTANCE = new BigDecimalTypeManufacturer();
    public static final MathContext CONTEXT = new MathContext(18, RoundingMode.HALF_EVEN);

    private BigDecimalTypeManufacturer() {
    }

    @Override
    public BigDecimal getType(DataProviderStrategy dataProviderStrategy, AttributeMetadata attributeMetadata,
            ManufacturingContext manufacturingContext) {

        double value = PodamUtils.getDoubleInRange(Double.parseDouble(MIN_FEEDBACK_SCORE_VALUE),
                Double.parseDouble(MAX_FEEDBACK_SCORE_VALUE));

        return new BigDecimal(value, CONTEXT).setScale(SCALE, CONTEXT.getRoundingMode());
    }
}
