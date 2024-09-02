package com.comet.opik.podam.manufacturer;

import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.util.UUID;

public class UUIDTypeManufacturer extends AbstractTypeManufacturer<UUID> {

    public static final UUIDTypeManufacturer INSTANCE = new UUIDTypeManufacturer();

    private static final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();

    private UUIDTypeManufacturer() {
    }

    @Override
    public UUID getType(DataProviderStrategy dataProviderStrategy, AttributeMetadata attributeMetadata,
            ManufacturingContext manufacturingContext) {
        return generator.generate();
    }
}
