package com.comet.opik.podam.manufacturer;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.RandomStringUtils;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.util.HashMap;
import java.util.List;

import static com.comet.opik.domain.ImageUtils.PREFIX_BMP;
import static com.comet.opik.domain.ImageUtils.PREFIX_GIF0;
import static com.comet.opik.domain.ImageUtils.PREFIX_GIF1;
import static com.comet.opik.domain.ImageUtils.PREFIX_JPEG;
import static com.comet.opik.domain.ImageUtils.PREFIX_PNG;
import static com.comet.opik.domain.ImageUtils.PREFIX_TIFF0;
import static com.comet.opik.domain.ImageUtils.PREFIX_TIFF1;
import static com.comet.opik.domain.ImageUtils.PREFIX_WEBP;

public class JsonNodeTypeManufacturer extends AbstractTypeManufacturer<JsonNode> {

    public static final JsonNodeTypeManufacturer INSTANCE = new JsonNodeTypeManufacturer();

    private static final List<String> FORBIDDEN_STRINGS = List.of(
            PREFIX_JPEG,
            PREFIX_PNG,
            PREFIX_GIF0,
            PREFIX_GIF1,
            PREFIX_BMP,
            PREFIX_TIFF0,
            PREFIX_TIFF1,
            PREFIX_WEBP);

    @Override
    public JsonNode getType(
            DataProviderStrategy dataProviderStrategy,
            AttributeMetadata attributeMetadata,
            ManufacturingContext manufacturingContext) {
        var map = new HashMap<>();
        for (var i = 0; i < 5; i++) {
            map.put(getValidRandomString(10), getValidRandomString(10));
        }
        return JsonUtils.getJsonNodeFromString(JsonUtils.writeValueAsString(map));
    }

    // verifies that no random string contains base64 image prefixes
    private String getValidRandomString(int count) {
        String randomString = RandomStringUtils.randomAlphanumeric(count);
        while (FORBIDDEN_STRINGS.stream().anyMatch(randomString::startsWith)) {
            randomString = RandomStringUtils.randomAlphanumeric(count);
        }

        return randomString;
    }
}
