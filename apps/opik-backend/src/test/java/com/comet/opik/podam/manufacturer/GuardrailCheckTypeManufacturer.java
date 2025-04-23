package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.Guardrail;
import com.comet.opik.api.GuardrailPiiDetails;
import com.comet.opik.api.GuardrailTopicDetails;
import com.comet.opik.api.GuardrailType;
import com.comet.opik.domain.GuardrailResult;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.RandomStringUtils;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.util.HashMap;
import java.util.List;
import java.util.Random;

public class GuardrailCheckTypeManufacturer extends AbstractTypeManufacturer<Guardrail> {
    public static final Random RANDOM = new Random();
    public static final GuardrailCheckTypeManufacturer INSTANCE = new GuardrailCheckTypeManufacturer();

    @Override
    public Guardrail getType(
            DataProviderStrategy dataProviderStrategy,
            AttributeMetadata attributeMetadata,
            ManufacturingContext manufacturingContext) {
        GuardrailType name = GuardrailType.values()[RANDOM.nextInt(GuardrailType.values().length)];
        GuardrailResult result = GuardrailResult.values()[RANDOM.nextInt(GuardrailResult.values().length)];

        return Guardrail.builder()
                .id(UUIDTypeManufacturer.INSTANCE.getType(dataProviderStrategy, attributeMetadata,
                        manufacturingContext))
                .secondaryId(UUIDTypeManufacturer.INSTANCE.getType(dataProviderStrategy, attributeMetadata,
                        manufacturingContext))
                .projectName(RandomStringUtils.randomAlphanumeric(10))
                .name(name)
                .result(result)
                .config(JsonNodeTypeManufacturer.INSTANCE.getType(dataProviderStrategy, attributeMetadata,
                        manufacturingContext))
                .details(generateDetails(name))
                .build();
    }

    private JsonNode generateDetails(GuardrailType name) {
        return switch (name) {
            case TOPIC -> JsonUtils.getJsonNodeFromString(JsonUtils.writeValueAsString(GuardrailTopicDetails.builder()
                    .scores(new HashMap<>() {
                        {
                            for (var i = 0; i < 5; i++) {
                                put(RandomStringUtils.randomAlphanumeric(10), RANDOM.nextFloat());
                            }
                        }
                    })
                    .build()));
            case PII -> JsonUtils.getJsonNodeFromString(JsonUtils.writeValueAsString(GuardrailPiiDetails.builder()
                    .detectedEntities(new HashMap<>() {
                        {
                            for (var i = 0; i < 5; i++) {
                                put(RandomStringUtils.randomAlphanumeric(10), List.of(
                                        GuardrailPiiDetails.Item.builder()
                                                .score(RANDOM.nextFloat())
                                                .build()));
                            }
                        }
                    })
                    .build()));
        };
    }
}
