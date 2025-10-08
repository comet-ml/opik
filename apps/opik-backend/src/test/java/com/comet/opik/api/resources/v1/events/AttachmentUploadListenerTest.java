package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.EntityType;
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

import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for AttachmentUploadListener which processes asynchronous attachment uploads.
 *
 * After refactoring, AttachmentUploadListener now uses AttachmentService.uploadAttachmentInternal()
 * for both MinIO and S3 backends. This simplifies the upload logic and makes it consistent across
 * both storage types.
 */
@ExtendWith(MockitoExtension.class)
class AttachmentUploadListenerTest {

    @Mock
    private AttachmentService attachmentService;
    @Mock
    private OpikConfiguration config;
    @Mock
    private S3Config s3Config;

    private AttachmentUploadListener listener;

    @BeforeEach
    void setUp() {
        // Use lenient stubbing since some tests may not invoke this (e.g., base64 decode failures)
        lenient().when(config.getS3Config()).thenReturn(s3Config);
        listener = new AttachmentUploadListener(attachmentService, config);
    }

    @Test
    void processAttachmentUpload__happyflow__shouldCallInternalUpload() {
        // Given
        byte[] fileData = "test content".getBytes();
        String base64Data = Base64.getEncoder().encodeToString(fileData);
        UUID entityId = UUID.randomUUID();
        AttachmentUploadRequested event = new AttachmentUploadRequested(
                "file.txt", "text/plain", base64Data, "workspace-id", "user-name",
                "project-name", entityId, EntityType.TRACE);

        // When
        listener.processAttachmentUpload(event);

        // Then - Verify uploadAttachmentInternal is called (works for both MinIO and S3)
        ArgumentCaptor<AttachmentInfo> attachmentCaptor = ArgumentCaptor.forClass(AttachmentInfo.class);
        ArgumentCaptor<byte[]> fileDataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(attachmentService).uploadAttachmentInternal(
                attachmentCaptor.capture(), fileDataCaptor.capture(), eq("workspace-id"), eq("user-name"));

        AttachmentInfo capturedAttachment = attachmentCaptor.getValue();
        assertThat(capturedAttachment.fileName()).isEqualTo("file.txt");
        assertThat(capturedAttachment.mimeType()).isEqualTo("text/plain");
        assertThat(capturedAttachment.entityType()).isEqualTo(EntityType.TRACE);
        assertThat(capturedAttachment.entityId()).isEqualTo(entityId);
        assertThat(capturedAttachment.projectName()).isEqualTo("project-name");

        byte[] capturedFileData = fileDataCaptor.getValue();
        assertThat(capturedFileData).isEqualTo(fileData);
    }

    @Test
    void processAttachmentUpload__spanEntity__shouldCallInternalUpload() {
        // Given
        byte[] fileData = "s3 test content".getBytes();
        String base64Data = Base64.getEncoder().encodeToString(fileData);
        UUID entityId = UUID.randomUUID();
        AttachmentUploadRequested event = new AttachmentUploadRequested(
                "s3-file.txt", "text/plain", base64Data, "workspace-id", "user-name",
                "project-name", entityId, EntityType.SPAN);

        // When
        listener.processAttachmentUpload(event);

        // Then - Verify uploadAttachmentInternal is called (same for both entity types)
        ArgumentCaptor<AttachmentInfo> attachmentCaptor = ArgumentCaptor.forClass(AttachmentInfo.class);
        ArgumentCaptor<byte[]> fileDataCaptor = ArgumentCaptor.forClass(byte[].class);
        verify(attachmentService).uploadAttachmentInternal(
                attachmentCaptor.capture(), fileDataCaptor.capture(), eq("workspace-id"), eq("user-name"));

        AttachmentInfo capturedAttachment = attachmentCaptor.getValue();
        assertThat(capturedAttachment.fileName()).isEqualTo("s3-file.txt");
        assertThat(capturedAttachment.mimeType()).isEqualTo("text/plain");
        assertThat(capturedAttachment.entityType()).isEqualTo(EntityType.SPAN);
        assertThat(capturedAttachment.entityId()).isEqualTo(entityId);
        assertThat(capturedAttachment.projectName()).isEqualTo("project-name");

        byte[] capturedFileData = fileDataCaptor.getValue();
        assertThat(capturedFileData).isEqualTo(fileData);
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

        // Then - No upload method should be called due to base64 decode failure
        verify(attachmentService, never()).uploadAttachmentInternal(any(), any(), any(), any());
    }

    @Test
    void processAttachmentUpload__attachmentServiceFailure__shouldHandleGracefully() {
        // Given
        byte[] fileData = "test content".getBytes();
        String base64Data = Base64.getEncoder().encodeToString(fileData);
        AttachmentUploadRequested event = new AttachmentUploadRequested(
                "fail-file.txt", "text/plain", base64Data, "workspace-id", "user-name",
                "project-name", UUID.randomUUID(), EntityType.TRACE);

        // Mock attachmentService to throw exception (use doThrow for void methods)
        doThrow(new RuntimeException("Storage service unavailable"))
                .when(attachmentService).uploadAttachmentInternal(any(), any(), any(), any());

        // When - This should not throw an exception, just log the error
        listener.processAttachmentUpload(event);

        // Then - uploadAttachmentInternal should be called but exception should be handled gracefully
        verify(attachmentService).uploadAttachmentInternal(any(), any(), eq("workspace-id"), eq("user-name"));
    }
}