package com.comet.opik.podam.manufacturer;

import com.fasterxml.jackson.databind.JsonNode;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import static com.comet.opik.api.DatasetItemInputValue.JsonValue;

public class DatasetItemInputJsonValueTypeManufacturer extends AbstractTypeManufacturer<JsonValue> {

    public static final DatasetItemInputJsonValueTypeManufacturer INSTANCE = new DatasetItemInputJsonValueTypeManufacturer();

    @Override
    public JsonValue getType(DataProviderStrategy dataProviderStrategy, AttributeMetadata attributeMetadata,
            ManufacturingContext manufacturingContext) {
        return new JsonValue(
                dataProviderStrategy.getTypeValue(attributeMetadata, manufacturingContext, JsonNode.class));
    }
}
