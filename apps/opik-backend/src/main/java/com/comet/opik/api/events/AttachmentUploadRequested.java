package com.comet.opik.api.events;

import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.UUID;

/**
 * Event fired when an attachment needs to be uploaded asynchronously.
 * Contains all information needed to process the upload in the background.
 */
@Getter
@Accessors(fluent = true)
public class AttachmentUploadRequested extends BaseEvent {

    private final @NonNull String fileName;
    private final @NonNull String mimeType;
    private final @NonNull String base64Data;
    private final @NonNull String projectName;
    private final @NonNull UUID entityId;
    private final @NonNull EntityType entityType;

    public AttachmentUploadRequested(
            @NonNull String fileName,
            @NonNull String mimeType,
            @NonNull String base64Data,
            @NonNull String workspaceId,
            @NonNull String userName,
            @NonNull String projectName,
            @NonNull UUID entityId,
            @NonNull EntityType entityType) {
        super(workspaceId, userName);
        this.fileName = fileName;
        this.mimeType = mimeType;
        this.base64Data = base64Data;
        this.projectName = projectName;
        this.entityId = entityId;
        this.entityType = entityType;
    }
}
