package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.ProviderApiKeyUpdate;
import org.apache.commons.lang3.RandomStringUtils;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

public class ProviderApiKeyUpdateManufacturer extends AbstractTypeManufacturer<ProviderApiKeyUpdate> {
    public static final ProviderApiKeyUpdateManufacturer INSTANCE = new ProviderApiKeyUpdateManufacturer();

    @Override
    public ProviderApiKeyUpdate getType(DataProviderStrategy strategy, AttributeMetadata metadata,
            ManufacturingContext context) {

        return new ProviderApiKeyUpdate(
                RandomStringUtils.secure().nextAlphabetic(20),
                strategy.getTypeValue(metadata, context, String.class));
    }
}
