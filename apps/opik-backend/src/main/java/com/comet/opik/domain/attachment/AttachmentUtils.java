package com.comet.opik.domain.attachment;

import lombok.experimental.UtilityClass;

@UtilityClass
public class AttachmentUtils {
    public static final String KEY_TEMPLATE = "opik/attachment/{workspaceId}/projects/{projectId}/{entity_type}/{entity_id}/files/{file_name}";
}
