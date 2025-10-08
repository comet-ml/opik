package com.comet.opik.api.events;

import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.podam.PodamFactoryUtils;
import org.junit.jupiter.api.Test;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AttachmentUploadRequestedTest {

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    @Test
    void constructor__shouldCreateEventWithAllFields() {
        // Given - Use PODAM for test data generation
        String fileName = factory.manufacturePojo(String.class);
        String mimeType = factory.manufacturePojo(String.class);
        String base64Data = factory.manufacturePojo(String.class);
        String workspaceId = factory.manufacturePojo(String.class);
        String userName = factory.manufacturePojo(String.class);
        String projectName = factory.manufacturePojo(String.class);
        UUID entityId = UUID.randomUUID();
        EntityType entityType = EntityType.TRACE;

        // When
        AttachmentUploadRequested event = new AttachmentUploadRequested(
                fileName, mimeType, base64Data, workspaceId, userName, projectName, entityId, entityType);

        // Then - Use assertJ object assertions
        assertThat(event)
                .isNotNull()
                .satisfies(e -> {
                    assertThat(e.fileName()).isEqualTo(fileName);
                    assertThat(e.mimeType()).isEqualTo(mimeType);
                    assertThat(e.base64Data()).isEqualTo(base64Data);
                    assertThat(e.workspaceId()).isEqualTo(workspaceId);
                    assertThat(e.userName()).isEqualTo(userName);
                    assertThat(e.projectName()).isEqualTo(projectName);
                    assertThat(e.entityId()).isEqualTo(entityId);
                    assertThat(e.entityType()).isEqualTo(entityType);
                });
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
