package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.CompleteMultipartUploadRequest;
import com.comet.opik.api.attachment.StartMultipartUploadRequest;
import com.comet.opik.api.attachment.StartMultipartUploadResponse;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

public class AttachmentResourceClient {
    private static final String RESOURCE_PATH = "%s/v1/private/attachment";

    private final ClientSupport client;
    private final String baseURI;

    public AttachmentResourceClient(ClientSupport client) {
        this.client = client;
        this.baseURI = "http://localhost:%d".formatted(client.getPort());
    }

    public StartMultipartUploadResponse startMultiPartUpload(
            StartMultipartUploadRequest request, String apiKey, String workspaceName, int expectedStatus) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("upload-start")
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
            if (expectedStatus == 200) {
                return actualResponse.readEntity(StartMultipartUploadResponse.class);
            }

            return null;
        }
    }

    public void completeMultiPartUpload(
            CompleteMultipartUploadRequest request, String apiKey, String workspaceName, int expectedStatus) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("upload-complete")
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        }
    }

    public void uploadAttachment(AttachmentInfo attachmentInfo, byte[] data,
            String apiKey, String workspaceName, int expectedStatus) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("upload")
                .queryParam("file_name", attachmentInfo.fileName())
                .queryParam("project_name", attachmentInfo.projectName())
                .queryParam("mime_type", attachmentInfo.mimeType())
                .queryParam("entity_type", attachmentInfo.entityType().getValue())
                .queryParam("entity_id", attachmentInfo.entityId())
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.entity(data, MediaType.APPLICATION_OCTET_STREAM))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        }
    }
}
