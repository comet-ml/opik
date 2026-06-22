package com.comet.opik.api.resources.v1.events.tools;

import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;
import org.apache.tika.Tika;

import java.util.List;

/**
 * Builds the compact {@code [{file_name, mime_type, media_type}]} attachment summary surfaced to
 * the judge on the {@code read} tool response ({@link ReadTool}): when the agent reads a trace it
 * sees the {@code file_name} it must pass to {@code get_attachment} to load the media. Centralized
 * so the summary shape stays in one place if more discovery surfaces are added later.
 */
@UtilityClass
public class AttachmentSummaries {

    private static final Tika TIKA = new Tika();

    /**
     * Serializes attachment info into a compact array. The {@code mime_type} falls back to a
     * Tika detection on the file name when the stored MIME is blank; {@code media_type} is the
     * coarse {@link MediaCategory} bucket (image / audio / video / other) the judge uses to decide
     * whether {@code get_attachment} can load it.
     */
    public static ArrayNode toJsonArray(@NonNull List<AttachmentInfo> attachments) {
        ArrayNode array = JsonUtils.getMapper().createArrayNode();
        for (var info : attachments) {
            String mime = StringUtils.isNotBlank(info.mimeType()) ? info.mimeType() : TIKA.detect(info.fileName());
            ObjectNode item = array.addObject();
            item.put("file_name", info.fileName());
            item.put("mime_type", mime);
            item.put("media_type", MediaCategory.fromMimeType(mime).name().toLowerCase());
        }
        return array;
    }
}
