package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.ProviderApiKeyUpdate;
import org.apache.commons.lang3.RandomStringUtils;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.util.Map;

public class ProviderApiKeyUpdateManufacturer extends AbstractTypeManufacturer<ProviderApiKeyUpdate> {
    public static final ProviderApiKeyUpdateManufacturer INSTANCE = new ProviderApiKeyUpdateManufacturer();

    @Override
    public ProviderApiKeyUpdate getType(DataProviderStrategy strategy, AttributeMetadata metadata,
            ManufacturingContext context) {

        return ProviderApiKeyUpdate.builder()
                .apiKey(RandomStringUtils.secure().nextAlphabetic(20))
                .name(strategy.getTypeValue(metadata, context, String.class))
                .providerName(strategy.getTypeValue(metadata, context, String.class))
                .headers(Map.of(
                        RandomStringUtils.secure().nextAlphabetic(5), RandomStringUtils.secure().nextAlphabetic(5),
                        RandomStringUtils.secure().nextAlphabetic(5), RandomStringUtils.secure().nextAlphabetic(5),
                        RandomStringUtils.secure().nextAlphabetic(5), RandomStringUtils.secure().nextAlphabetic(5),
                        RandomStringUtils.secure().nextAlphabetic(5), RandomStringUtils.secure().nextAlphabetic(5),
                        RandomStringUtils.secure().nextAlphabetic(5), RandomStringUtils.secure().nextAlphabetic(5)))
                .configuration(Map.of(
                        RandomStringUtils.secure().nextAlphabetic(5), RandomStringUtils.secure().nextAlphabetic(5),
                        RandomStringUtils.secure().nextAlphabetic(5), RandomStringUtils.secure().nextAlphabetic(5),
                        RandomStringUtils.secure().nextAlphabetic(5), RandomStringUtils.secure().nextAlphabetic(5),
                        RandomStringUtils.secure().nextAlphabetic(5), RandomStringUtils.secure().nextAlphabetic(5),
                        RandomStringUtils.secure().nextAlphabetic(5), RandomStringUtils.secure().nextAlphabetic(5)))
                .baseUrl("http://" + strategy.getTypeValue(metadata, context, String.class) + ".com")
                .build();
    }
}
