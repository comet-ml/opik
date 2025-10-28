package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.attachment.Attachment;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.CompleteMultipartUploadRequest;
import com.comet.opik.api.attachment.DeleteAttachmentsRequest;
import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.api.attachment.StartMultipartUploadRequest;
import com.comet.opik.api.attachment.StartMultipartUploadResponse;
import com.comet.opik.api.resources.utils.TestUtils;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import org.apache.commons.io.IOUtils;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

public class AttachmentResourceClient {
    private static final String RESOURCE_PATH = "%s/v1/private/attachment";

    private final ClientSupport client;
    private final Client externatClient;
    private final String baseURI;

    public AttachmentResourceClient(ClientSupport client) {
        this.client = client;
        this.externatClient = ClientBuilder.newClient();
        this.baseURI = TestUtils.getBaseUrl(client);
    }

    public void close() {
        externatClient.close();
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

    public Attachment.AttachmentPage attachmentList(UUID projectId, EntityType entityType, UUID entityId, String path,
            String apiKey, String workspaceName, int expectedStatus) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("list")
                .queryParam("path", path)
                .queryParam("project_id", projectId)
                .queryParam("entity_type", entityType.getValue())
                .queryParam("entity_id", entityId)
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

            if (expectedStatus == 200) {
                return actualResponse.readEntity(Attachment.AttachmentPage.class);
            }

            return null;
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

    public void downloadAttachment(String fileName, UUID projectId, String mimeType, EntityType entityType,
            UUID entityId,
            String apiKey, String workspaceName, int expectedStatus) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("download")
                .queryParam("file_name", fileName)
                .queryParam("container_id", projectId)
                .queryParam("mime_type", mimeType)
                .queryParam("entity_type", entityType.getValue())
                .queryParam("entity_id", entityId)
                .request()
                .accept(mimeType)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        }
    }

    public void deleteAttachments(
            DeleteAttachmentsRequest request, String apiKey, String workspaceName, int expectedStatus) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("delete")
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        }
    }

    public void uploadFile(StartMultipartUploadResponse startUploadResponse, byte[] data, String apiKey,
            String workspaceName) {
        try (var response = externatClient.target(startUploadResponse.preSignUrls().getFirst())
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.entity(data, "*/*"))) {
            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(204);
        }
    }

    public String uploadFileExternal(String url, byte[] data) {
        try (var response = externatClient.target(url)
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .put(Entity.entity(data, "*/*"))) {
            return response.getHeaders().get("ETag").getFirst().toString();
        }
    }

    public byte[] downloadFile(String url, String apiKey, int expectedStatus) throws IOException {
        try (var response = externatClient.target(url)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .get()) {
            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

            if (expectedStatus == 200) {
                InputStream inputStream = response.readEntity(InputStream.class);

                return IOUtils.toByteArray(inputStream);
            }

            return null;
        }
    }

    public byte[] downloadFileExternal(String url, int expectedStatus) throws IOException {
        try (var response = externatClient.target(url)
                .request()
                .get()) {
            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);

            if (expectedStatus == 200) {
                InputStream inputStream = response.readEntity(InputStream.class);

                return IOUtils.toByteArray(inputStream);
            }

            return null;
        }
    }
}
