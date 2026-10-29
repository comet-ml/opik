package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.AnnotationQueueAnnotatorView;
import org.apache.commons.lang3.RandomStringUtils;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

public class AnnotationQueueAnnotatorViewManufacturer extends AbstractTypeManufacturer<AnnotationQueueAnnotatorView> {

    public static final AnnotationQueueAnnotatorViewManufacturer INSTANCE = new AnnotationQueueAnnotatorViewManufacturer();

    @Override
    public AnnotationQueueAnnotatorView getType(DataProviderStrategy strategy, AttributeMetadata metadata,
            ManufacturingContext context) {
        return AnnotationQueueAnnotatorView.builder()
                .html("<div>" + RandomStringUtils.secure().nextAlphanumeric(20) + "</div>")
                .kitVersion(AnnotationQueueAnnotatorView.LATEST_KIT_VERSION)
                .summary("Annotators see " + RandomStringUtils.secure().nextAlphanumeric(20))
                .build();
    }
}
