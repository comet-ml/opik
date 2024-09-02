package com.comet.opik.podam.manufacturer;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.RandomStringUtils;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.util.HashMap;

public class JsonNodeTypeManufacturer extends AbstractTypeManufacturer<JsonNode> {

    public static final JsonNodeTypeManufacturer INSTANCE = new JsonNodeTypeManufacturer();

    @Override
    public JsonNode getType(
            DataProviderStrategy dataProviderStrategy,
            AttributeMetadata attributeMetadata,
            ManufacturingContext manufacturingContext) {
        var map = new HashMap<>();
        for (var i = 0; i < 5; i++) {
            map.put(RandomStringUtils.randomAlphanumeric(10), RandomStringUtils.randomAlphanumeric(10));
        }
        return JsonUtils.getJsonNodeFromString(JsonUtils.writeValueAsString(map));
    }
}
