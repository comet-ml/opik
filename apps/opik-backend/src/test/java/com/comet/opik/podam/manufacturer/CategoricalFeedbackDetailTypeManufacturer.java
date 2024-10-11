package com.comet.opik.podam.manufacturer;

import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.api.PodamUtils;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.comet.opik.api.FeedbackDefinition.CategoricalFeedbackDefinition.CategoricalFeedbackDetail;
import static com.comet.opik.utils.ValidationUtils.SCALE;

public class CategoricalFeedbackDetailTypeManufacturer extends AbstractTypeManufacturer<CategoricalFeedbackDetail> {

    @Override
    public CategoricalFeedbackDetail getType(DataProviderStrategy dataProviderStrategy,
            AttributeMetadata attributeMetadata, ManufacturingContext manufacturingContext) {

        var generatedValues = new HashSet<Double>();

        Map<String, Double> categories = IntStream.range(0, 5)
                .mapToObj(i -> {
                    var name = PodamUtils.getNiceString(10);
                    var value = BigDecimal.valueOf(getNewValue(generatedValues))
                            .setScale(SCALE, RoundingMode.HALF_EVEN)
                            .doubleValue();

                    return Map.entry(name, value);
                }).collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        return CategoricalFeedbackDetail.builder()
                .categories(categories)
                .build();
    }

    private double getNewValue(Set<Double> generatedValues) {

        double value;

        do {
            value = PodamUtils.getDoubleInRange(0, 10);
        } while (generatedValues.contains(value));

        generatedValues.add(value);

        return value;
    }

}
