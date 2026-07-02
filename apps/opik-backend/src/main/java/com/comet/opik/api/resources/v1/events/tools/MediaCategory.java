package com.comet.opik.api.resources.v1.events.tools;

import org.apache.commons.lang3.StringUtils;

/**
 * Coarse media classification for attachments the judge can load via
 * {@link GetAttachmentTool}. Maps to the langchain4j multimodal content types
 * ({@code ImageContent} / {@code AudioContent} / {@code VideoContent}) that
 * {@link MediaMessageBuilder} injects into the conversation.
 *
 * <p>{@link #OTHER} covers everything that cannot be shown to a model as
 * viewable media (pdf, text, json, …) — those attachments are reported in list
 * mode but declined in fetch mode.
 */
public enum MediaCategory {
    IMAGE,
    AUDIO,
    VIDEO,
    OTHER;

    /**
     * Classifies a MIME type by its top-level category. Blank / unknown MIME
     * types resolve to {@link #OTHER}; callers that have a filename should
     * resolve the MIME type (e.g. via Tika) before calling this.
     */
    public static MediaCategory fromMimeType(String mimeType) {
        if (StringUtils.isBlank(mimeType)) {
            return OTHER;
        }
        String normalized = mimeType.toLowerCase();
        if (normalized.startsWith("image/")) {
            return IMAGE;
        }
        if (normalized.startsWith("audio/")) {
            return AUDIO;
        }
        if (normalized.startsWith("video/")) {
            return VIDEO;
        }
        return OTHER;
    }

    public boolean isViewable() {
        return this != OTHER;
    }
}
