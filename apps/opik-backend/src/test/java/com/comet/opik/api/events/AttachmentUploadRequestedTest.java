package com.comet.opik.api.events;

import com.comet.opik.api.attachment.EntityType;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentUploadRequestedTest {

    @Test
    void constructor__shouldCreateEventWithAllFields() {
        // Given
        String fileName = "test-file.txt";
        String mimeType = "text/plain";
        String base64Data = "dGVzdCBjb250ZW50";
        String workspaceId = "workspace-123";
        String userName = "test-user";
        String projectName = "test-project";
        UUID entityId = UUID.randomUUID();
        EntityType entityType = EntityType.TRACE;

        // When
        AttachmentUploadRequested event = new AttachmentUploadRequested(
                fileName, mimeType, base64Data, workspaceId, userName, projectName, entityId, entityType);

        // Then
        assertThat(event.fileName()).isEqualTo(fileName);
        assertThat(event.mimeType()).isEqualTo(mimeType);
        assertThat(event.base64Data()).isEqualTo(base64Data);
        assertThat(event.workspaceId()).isEqualTo(workspaceId);
        assertThat(event.userName()).isEqualTo(userName);
        assertThat(event.projectName()).isEqualTo(projectName);
        assertThat(event.entityId()).isEqualTo(entityId);
        assertThat(event.entityType()).isEqualTo(entityType);
    }

    @Test
    void baseEventFields__shouldBeInitialized() {
        // Given
        AttachmentUploadRequested event = new AttachmentUploadRequested(
                "file.txt", "text/plain", "data", "ws-id", "user", "project", UUID.randomUUID(), EntityType.SPAN);

        // Then
        assertThat(event.workspaceId()).isNotNull();
        assertThat(event.userName()).isNotNull();
        // BaseEvent should have initialized timestamp and other base fields
    }
}
