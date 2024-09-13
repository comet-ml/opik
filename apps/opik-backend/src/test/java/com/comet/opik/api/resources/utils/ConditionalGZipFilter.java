package com.comet.opik.api.resources.utils;

import com.comet.opik.utils.JsonUtils;
import jakarta.ws.rs.client.ClientRequestContext;
import jakarta.ws.rs.client.ClientRequestFilter;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.zip.GZIPOutputStream;

public class ConditionalGZipFilter implements ClientRequestFilter {

    private static final int GZIP_THRESHOLD_500KB = 500 * 1024;

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {

        if (requestContext.hasEntity() && MediaType.APPLICATION_JSON_TYPE.equals(requestContext.getMediaType())) {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            JsonUtils.writeValueAsString(baos, requestContext.getEntity()); // Serialize the entity to byte array

            byte[] entityBytes = baos.toByteArray();
            int entitySize = entityBytes.length;

            if (entitySize > GZIP_THRESHOLD_500KB) {
                ByteArrayOutputStream compressedBaos = new ByteArrayOutputStream();
                try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(compressedBaos)) {
                    gzipOutputStream.write(entityBytes);
                }

                byte[] compressedEntity = compressedBaos.toByteArray();
                requestContext.setEntity(compressedEntity, null, MediaType.APPLICATION_JSON_TYPE);
                requestContext.getHeaders().add(HttpHeaders.CONTENT_ENCODING, "gzip");
            } else {
                // Use the original entity if below the threshold
                requestContext.setEntity(entityBytes, null, MediaType.APPLICATION_JSON_TYPE);
            }
        }
    }
}
