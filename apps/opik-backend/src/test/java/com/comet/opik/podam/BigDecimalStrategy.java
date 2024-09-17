package com.comet.opik.podam;

import com.comet.opik.utils.ValidationUtils;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import uk.co.jemos.podam.api.PodamUtils;
import uk.co.jemos.podam.common.AttributeStrategy;
import uk.co.jemos.podam.common.BeanValidationStrategy;

import java.lang.annotation.Annotation;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class BigDecimalStrategy implements AttributeStrategy<BigDecimal> {

    public static final BigDecimalStrategy INSTANCE = new BigDecimalStrategy();

    @Override
    public BigDecimal getValue(Class<?> attrType, List<Annotation> annotations) {
        var min = ValidationUtils.MIN_FEEDBACK_SCORE_VALUE;
        var decimalMin = BeanValidationStrategy.findTypeFromList(annotations, DecimalMin.class);
        if (null != decimalMin) {
            min = decimalMin.value();
        }
        var max = ValidationUtils.MAX_FEEDBACK_SCORE_VALUE;
        var decimalMax = BeanValidationStrategy.findTypeFromList(annotations, DecimalMax.class);
        if (null != decimalMax) {
            max = decimalMax.value();
        }
        var value = PodamUtils.getDoubleInRange(Double.parseDouble(min), Double.parseDouble(max));
        return new BigDecimal(value).setScale(ValidationUtils.SCALE, RoundingMode.HALF_EVEN);
    }
}
