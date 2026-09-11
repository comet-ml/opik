package com.comet.opik.podam.manufacturer;

import jakarta.validation.constraints.Positive;
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

    // Smallest strictly-positive value representable at SCALE decimal places (10^-SCALE). A value >= this
    // rounds (HALF_EVEN) to a positive number at SCALE, so a @Positive field never manufactures 0.
    // Derived from SCALE so it tracks the scale rather than hardcoding a literal.
    private static final double MIN_POSITIVE_VALUE = Math.pow(10, -SCALE);

    private BigDecimalTypeManufacturer() {
    }

    @Override
    public BigDecimal getType(DataProviderStrategy dataProviderStrategy, AttributeMetadata attributeMetadata,
            ManufacturingContext manufacturingContext) {

        // @Positive BigDecimal fields (e.g. maxCostUsd, now @Valid-cascaded at the API boundary) must
        // manufacture a strictly-positive value, or ~half the generated values would fail validation and
        // flake create tests. Handled here — scoped to BigDecimal fields — rather than via a global
        // @Positive attribute strategy, which would hand a BigDecimal to @Positive int/long/Double fields
        // and make Podam throw.
        double min = hasPositiveConstraint(attributeMetadata)
                ? MIN_POSITIVE_VALUE
                : Double.parseDouble(MIN_FEEDBACK_SCORE_VALUE);

        double value = PodamUtils.getDoubleInRange(min, Double.parseDouble(MAX_FEEDBACK_SCORE_VALUE));

        return new BigDecimal(value, CONTEXT).setScale(SCALE, CONTEXT.getRoundingMode());
    }

    private static boolean hasPositiveConstraint(AttributeMetadata attributeMetadata) {
        var annotations = attributeMetadata.getAttributeAnnotations();
        return annotations != null && annotations.stream().anyMatch(Positive.class::isInstance);
    }
}
