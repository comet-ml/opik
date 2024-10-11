package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.DatasetItemInputValue;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.util.Random;

public class DatasetItemInputValueTypeManufacturer extends AbstractTypeManufacturer<DatasetItemInputValue> {

    public static final Random RANDOM = new Random();
    public static final DatasetItemInputValueTypeManufacturer INSTANCE = new DatasetItemInputValueTypeManufacturer();

    @Override
    public DatasetItemInputValue<?> getType(DataProviderStrategy dataProviderStrategy,
            AttributeMetadata attributeMetadata, ManufacturingContext manufacturingContext) {

        if (RANDOM.nextBoolean()) {
            return dataProviderStrategy.getTypeValue(attributeMetadata, manufacturingContext,
                    DatasetItemInputValue.StringValue.class);
        } else {
            return dataProviderStrategy.getTypeValue(attributeMetadata, manufacturingContext,
                    DatasetItemInputValue.JsonValue.class);
        }

    }
}
