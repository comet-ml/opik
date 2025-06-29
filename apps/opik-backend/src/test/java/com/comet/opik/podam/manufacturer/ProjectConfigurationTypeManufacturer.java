package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.Project;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

public class ProjectConfigurationTypeManufacturer extends AbstractTypeManufacturer<Project.Configuration> {
    @Override
    public Project.Configuration getType(DataProviderStrategy dataProviderStrategy, AttributeMetadata attributeMetadata,
            ManufacturingContext manufacturingContext) {
        return Project.Configuration.builder()
                .timeoutToMarkThreadAsInactive(null) // by default, no timeout
                .build();
    }
}
