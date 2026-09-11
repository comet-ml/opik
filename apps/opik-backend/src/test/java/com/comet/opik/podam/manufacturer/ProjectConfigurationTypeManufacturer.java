package com.comet.opik.podam.manufacturer;

import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import static com.comet.opik.api.Project.Configuration;

public class ProjectConfigurationTypeManufacturer extends AbstractTypeManufacturer<Configuration> {

    public static final ProjectConfigurationTypeManufacturer INSTANCE = new ProjectConfigurationTypeManufacturer();

    @Override
    public Configuration getType(DataProviderStrategy dataProviderStrategy, AttributeMetadata attributeMetadata,
            ManufacturingContext manufacturingContext) {
        return Configuration.builder()
                .timeoutToMarkThreadAsInactive(null) // by default, no timeout
                .build();
    }

}
