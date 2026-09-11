package com.comet.opik.api.attachment;

import java.util.UUID;

public interface AttachmentInfoHolder {
    UUID containerId();
    EntityType entityType();
    UUID entityId();
    String fileName();
    String mimeType();
}
