package com.comet.opik.api.resources.v1.events.tools;

import lombok.NonNull;

/**
 * A single attachment the judge asked {@link GetAttachmentTool} to load, staged
 * on {@link TraceToolContext} for {@link ToolCallLoop} to drain and inject as
 * multimodal content into the conversation.
 *
 * <p>Exactly one of {@link #base64Data} / {@link #url} is set, depending on the
 * storage backend (OPIK-6555 Phase 1 decision):
 * <ul>
 *   <li><b>MinIO</b> — {@link #base64Data} holds the raw bytes Base64-encoded.
 *       MinIO is typically on an internal network, so a presigned MinIO URL would
 *       not be reachable by an external provider; inlining the bytes avoids that.</li>
 *   <li><b>S3</b> — {@link #url} holds a publicly reachable presigned download URL;
 *       inlining multi-MB bytes per round is avoided.</li>
 * </ul>
 */
public record MediaPayload(
        @NonNull String fileName,
        @NonNull String mimeType,
        @NonNull MediaCategory category,
        long sizeBytes,
        String base64Data,
        String url) {

    public static MediaPayload ofBase64(String fileName, String mimeType, MediaCategory category, long sizeBytes,
            String base64Data) {
        return new MediaPayload(fileName, mimeType, category, sizeBytes, base64Data, null);
    }

    public static MediaPayload ofUrl(String fileName, String mimeType, MediaCategory category, long sizeBytes,
            String url) {
        return new MediaPayload(fileName, mimeType, category, sizeBytes, null, url);
    }
}
