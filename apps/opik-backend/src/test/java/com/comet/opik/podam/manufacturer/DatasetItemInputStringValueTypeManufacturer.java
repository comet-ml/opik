package com.comet.opik.podam.manufacturer;

import org.apache.commons.lang3.RandomStringUtils;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import static com.comet.opik.api.DatasetItemInputValue.StringValue;

public class DatasetItemInputStringValueTypeManufacturer extends AbstractTypeManufacturer<StringValue> {

    public static final DatasetItemInputStringValueTypeManufacturer INSTANCE = new DatasetItemInputStringValueTypeManufacturer();

    @Override
    public StringValue getType(DataProviderStrategy dataProviderStrategy, AttributeMetadata attributeMetadata,
            ManufacturingContext manufacturingContext) {
        return new StringValue(RandomStringUtils.randomAlphanumeric(10));
    }
}
