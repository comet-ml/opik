package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.CompleteMultipartUploadRequest;
import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.api.attachment.StartMultipartUploadRequest;
import com.comet.opik.api.attachment.StartMultipartUploadResponse;
import com.comet.opik.api.events.AttachmentUploadRequested;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.S3Config;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AttachmentUploadListenerTest {

    @Mock
    private AttachmentService attachmentService;
    @Mock
    private OpikConfiguration config;
    @Mock
    private S3Config s3Config;
    @Mock
    private HttpClient httpClient;

    private AttachmentUploadListener listener;

    @BeforeEach
    void setUp() {
        // Use lenient stubbing since some tests may not invoke this (e.g., base64 decode failures)
        lenient().when(config.getS3Config()).thenReturn(s3Config);
        listener = new AttachmentUploadListener(attachmentService, config);
        // Use reflection to set the HttpClient mock for testing
        setHttpClientField(listener, httpClient);
    }

    @Test
    void processAttachmentUpload__minioConfig__shouldCallDirectUpload() {
        // Given
        when(s3Config.isMinIO()).thenReturn(true);
        byte[] fileData = "test content".getBytes();
        String base64Data = Base64.getEncoder().encodeToString(fileData);
        UUID entityId = UUID.randomUUID();
        AttachmentUploadRequested event = new AttachmentUploadRequested(
                "file.txt", "text/plain", base64Data, "workspace-id", "user-name",
                "project-name", entityId, EntityType.TRACE);

        // When
        listener.processAttachmentUpload(event);

        // Then - Verify MinIO direct upload is called
        ArgumentCaptor<AttachmentInfo> attachmentCaptor = ArgumentCaptor.forClass(AttachmentInfo.class);
        verify(attachmentService).uploadAttachment(
                attachmentCaptor.capture(), eq(fileData), eq("workspace-id"), eq("user-name"));

        AttachmentInfo capturedAttachment = attachmentCaptor.getValue();
        assertThat(capturedAttachment.fileName()).isEqualTo("file.txt");
        assertThat(capturedAttachment.mimeType()).isEqualTo("text/plain");
        assertThat(capturedAttachment.entityType()).isEqualTo(EntityType.TRACE);
        assertThat(capturedAttachment.entityId()).isEqualTo(entityId);

        // Verify S3 methods are not called
        verify(attachmentService, never()).startMultiPartUpload(any(), any(), any());
        verify(attachmentService, never()).completeMultiPartUpload(any(), any(), any());
    }

    @Test
    void processAttachmentUpload__s3Config__shouldCallMultipartUpload() throws IOException, InterruptedException {
        // Given
        when(s3Config.isMinIO()).thenReturn(false);
        byte[] fileData = "s3 test content".getBytes();
        String base64Data = Base64.getEncoder().encodeToString(fileData);
        UUID entityId = UUID.randomUUID();
        AttachmentUploadRequested event = new AttachmentUploadRequested(
                "s3-file.txt", "text/plain", base64Data, "workspace-id", "user-name",
                "project-name", entityId, EntityType.SPAN);

        // Mock startMultiPartUpload response
        StartMultipartUploadResponse startResponse = StartMultipartUploadResponse.builder()
                .uploadId("test-upload-id")
                .preSignUrls(Collections.singletonList("http://test-presigned-url.com/upload"))
                .build();
        when(attachmentService.startMultiPartUpload(any(StartMultipartUploadRequest.class), eq("workspace-id"),
                eq("user-name")))
                .thenReturn(startResponse);

        // Mock HTTP client response for presigned URL upload
        HttpResponse<String> httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(200);
        java.net.http.HttpHeaders headers = mock(java.net.http.HttpHeaders.class);
        when(headers.firstValue("ETag")).thenReturn(Optional.of("\"test-etag-123\""));
        when(httpResponse.headers()).thenReturn(headers);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        // When
        listener.processAttachmentUpload(event);

        // Then - Verify S3 multipart upload flow
        ArgumentCaptor<StartMultipartUploadRequest> startRequestCaptor = ArgumentCaptor
                .forClass(StartMultipartUploadRequest.class);
        verify(attachmentService).startMultiPartUpload(startRequestCaptor.capture(), eq("workspace-id"),
                eq("user-name"));

        StartMultipartUploadRequest startRequest = startRequestCaptor.getValue();
        assertThat(startRequest.fileName()).isEqualTo("s3-file.txt");
        assertThat(startRequest.mimeType()).isEqualTo("text/plain");
        assertThat(startRequest.entityType()).isEqualTo(EntityType.SPAN);
        assertThat(startRequest.entityId()).isEqualTo(entityId);

        // Verify HTTP PUT request to presigned URL
        ArgumentCaptor<HttpRequest> httpRequestCaptor = ArgumentCaptor.forClass(HttpRequest.class);
        verify(httpClient).send(httpRequestCaptor.capture(), any(HttpResponse.BodyHandler.class));

        HttpRequest httpRequest = httpRequestCaptor.getValue();
        assertThat(httpRequest.method()).isEqualTo("PUT");
        assertThat(httpRequest.uri().toString()).isEqualTo("http://test-presigned-url.com/upload");

        // Verify complete multipart upload
        ArgumentCaptor<CompleteMultipartUploadRequest> completeRequestCaptor = ArgumentCaptor
                .forClass(CompleteMultipartUploadRequest.class);
        verify(attachmentService).completeMultiPartUpload(completeRequestCaptor.capture(), eq("workspace-id"),
                eq("user-name"));

        CompleteMultipartUploadRequest completeRequest = completeRequestCaptor.getValue();
        assertThat(completeRequest.uploadId()).isEqualTo("test-upload-id");
        assertThat(completeRequest.fileName()).isEqualTo("s3-file.txt");
        assertThat(completeRequest.uploadedFileParts()).hasSize(1);
        assertThat(completeRequest.uploadedFileParts().get(0).eTag()).isEqualTo("test-etag-123"); // Quotes removed

        // Verify MinIO method is not called
        verify(attachmentService, never()).uploadAttachment(any(), any(), any(), any());
    }

    @Test
    void processAttachmentUpload__invalidBase64__shouldHandleGracefully() {
        // Given
        // Use lenient stubbing as this is never actually called (base64 decode fails first)
        lenient().when(s3Config.isMinIO()).thenReturn(true);
        String invalidBase64Data = "invalid-base64-data!@#$%";
        AttachmentUploadRequested event = new AttachmentUploadRequested(
                "invalid.txt", "text/plain", invalidBase64Data, "workspace-id", "user-name",
                "project-name", UUID.randomUUID(), EntityType.TRACE);

        // When - This should not throw an exception, just log the error
        listener.processAttachmentUpload(event);

        // Then - No upload methods should be called due to base64 decode failure
        verify(attachmentService, never()).uploadAttachment(any(), any(), any(), any());
        verify(attachmentService, never()).startMultiPartUpload(any(), any(), any());
        verify(attachmentService, never()).completeMultiPartUpload(any(), any(), any());
    }

    @Test
    void processAttachmentUpload__s3HttpFailure__shouldHandleGracefully() throws IOException, InterruptedException {
        // Given
        when(s3Config.isMinIO()).thenReturn(false);
        byte[] fileData = "test content".getBytes();
        String base64Data = Base64.getEncoder().encodeToString(fileData);
        AttachmentUploadRequested event = new AttachmentUploadRequested(
                "fail-file.txt", "text/plain", base64Data, "workspace-id", "user-name",
                "project-name", UUID.randomUUID(), EntityType.TRACE);

        // Mock startMultiPartUpload response
        StartMultipartUploadResponse startResponse = StartMultipartUploadResponse.builder()
                .uploadId("test-upload-id")
                .preSignUrls(Collections.singletonList("http://test-presigned-url.com/upload"))
                .build();
        when(attachmentService.startMultiPartUpload(any(), eq("workspace-id"), eq("user-name")))
                .thenReturn(startResponse);

        // Mock HTTP client to return error status
        HttpResponse<String> httpResponse = mock(HttpResponse.class);
        when(httpResponse.statusCode()).thenReturn(403); // Forbidden (non-retryable)
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(httpResponse);

        // When - This should not throw an exception, just log the error
        listener.processAttachmentUpload(event);

        // Then - Start should be called but complete should not be called due to HTTP failure
        // Note: 403 is non-retryable, so httpClient.send() should be called only once (no retries)
        verify(attachmentService).startMultiPartUpload(any(), eq("workspace-id"), eq("user-name"));
        verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        verify(attachmentService, never()).completeMultiPartUpload(any(), any(), any());
        verify(attachmentService, never()).uploadAttachment(any(), any(), any(), any());
    }

    @Test
    void processAttachmentUpload__attachmentServiceFailure__shouldHandleGracefully() {
        // Given
        when(s3Config.isMinIO()).thenReturn(true); // Use MinIO path for simplicity
        byte[] fileData = "test content".getBytes();
        String base64Data = Base64.getEncoder().encodeToString(fileData);
        AttachmentUploadRequested event = new AttachmentUploadRequested(
                "fail-file.txt", "text/plain", base64Data, "workspace-id", "user-name",
                "project-name", UUID.randomUUID(), EntityType.TRACE);

        // Mock attachmentService to throw exception (use doThrow for void methods)
        doThrow(new RuntimeException("Storage service unavailable"))
                .when(attachmentService).uploadAttachment(any(), any(), any(), any());

        // When - This should not throw an exception, just log the error
        listener.processAttachmentUpload(event);

        // Then - uploadAttachment should be called but exception should be handled
        verify(attachmentService).uploadAttachment(any(), any(), eq("workspace-id"), eq("user-name"));
    }

    // Helper method to set HttpClient field via reflection for testing
    private void setHttpClientField(AttachmentUploadListener listener, HttpClient httpClient) {
        try {
            var field = AttachmentUploadListener.class.getDeclaredField("httpClient");
            field.setAccessible(true);
            field.set(listener, httpClient);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set HttpClient field for testing", e);
        }
    }
}