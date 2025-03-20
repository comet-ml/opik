package com.comet.opik.podam.manufacturer;

import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.api.attachment.StartMultipartUploadRequest;
import org.apache.tika.Tika;
import uk.co.jemos.podam.api.AttributeMetadata;
import uk.co.jemos.podam.api.DataProviderStrategy;
import uk.co.jemos.podam.api.PodamUtils;
import uk.co.jemos.podam.common.ManufacturingContext;
import uk.co.jemos.podam.typeManufacturers.AbstractTypeManufacturer;

import java.util.List;
import java.util.UUID;

public class StartMultipartUploadRequestManufacturer extends AbstractTypeManufacturer<StartMultipartUploadRequest> {
    public static final StartMultipartUploadRequestManufacturer INSTANCE = new StartMultipartUploadRequestManufacturer();

    private static final List<String> FILE_EXTENSIONS = List.of("jpg", "jpeg", "png", "gif", "bmp", "webp", "txt",
            "json", "css", "xml", "rtf", "mp4");
    private static final Tika tika = new Tika();

    @Override
    public StartMultipartUploadRequest getType(DataProviderStrategy strategy, AttributeMetadata metadata,
            ManufacturingContext context) {
        String fileName = randomFileName();
        return StartMultipartUploadRequest.builder()
                .fileName(fileName)
                .mimeType(PodamUtils.getIntegerInRange(0, 1) == 1 ? null : tika.detect(fileName))
                .numOfFileParts(PodamUtils.getIntegerInRange(1, 3))
                .entityType(randomEntityType())
                .entityId(strategy.getTypeValue(metadata, context, UUID.class))
                .projectName(strategy.getTypeValue(metadata, context, String.class))
                .path(strategy.getTypeValue(metadata, context, String.class))
                .build();
    }

    String randomFileName() {
        return UUID.randomUUID() + "."
                + FILE_EXTENSIONS.get(PodamUtils.getIntegerInRange(0, FILE_EXTENSIONS.size() - 1));
    }

    EntityType randomEntityType() {
        return EntityType.values()[PodamUtils.getIntegerInRange(0, EntityType.values().length - 1)];
    }

}
