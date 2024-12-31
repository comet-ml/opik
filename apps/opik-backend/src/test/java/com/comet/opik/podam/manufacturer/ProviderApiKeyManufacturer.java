package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ProviderApiKey;
import org.apache.commons.lang3.RandomStringUtils;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.api.PodamUtils;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.time.Instant;
import java.util.UUID;

public class ProviderApiKeyManufacturer extends AbstractTypeManufacturer<ProviderApiKey> {
    public static final ProviderApiKeyManufacturer INSTANCE = new ProviderApiKeyManufacturer();

    @Override
    public ProviderApiKey getType(DataProviderStrategy strategy, AttributeMetadata metadata,
            ManufacturingContext context) {

        UUID id = strategy.getTypeValue(metadata, context, UUID.class);

        return ProviderApiKey.builder()
                .id(id)
                .provider(randomLlmProvider())
                .apiKey(RandomStringUtils.randomAlphanumeric(20))
                .name(strategy.getTypeValue(metadata, context, String.class))
                .createdBy(strategy.getTypeValue(metadata, context, String.class))
                .createdAt(Instant.now())
                .build();
    }

    public LlmProvider randomLlmProvider() {
        return LlmProvider.values()[PodamUtils.getIntegerInRange(0, LlmProvider.values().length - 1)];
    }
}
