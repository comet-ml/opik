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
 * Builds the compact {@code [{type, id, file_name, mime_type, media_type}]} attachment summary
 * surfaced to the judge on the {@code read} tool response ({@link ReadTool}) and in the injected
 * {@code {{trace}}} / {@code {{span}}} structures: each entry is <em>self-describing</em> — it carries
 * the owning {@code type} ({@code trace}/{@code span}) and {@code id} alongside the {@code file_name},
 * so the judge copies the exact {@code get_attachment} arguments verbatim instead of inferring the
 * owner (e.g. mistakenly anchoring on a {@code trace_id} nested in a span's body). Centralized so the
 * summary shape stays in one place across every discovery surface.
 */
@UtilityClass
public class AttachmentSummaries {

    private static final Tika TIKA = new Tika();

    /**
     * Serializes attachment info into a compact array. Each entry leads with its owning {@code type}
     * + {@code id} (taken from the {@link AttachmentInfo}'s {@code entityType}/{@code entityId}, which
     * are always populated) so the three values map 1:1 onto {@code get_attachment}'s
     * {@code type}/{@code id}/{@code file_name} arguments. The {@code mime_type} falls back to a Tika
     * detection on the file name when the stored MIME is blank; {@code media_type} is the coarse
     * {@link MediaCategory} bucket (image / audio / video / other) the judge uses to decide whether
     * {@code get_attachment} can load it.
     */
    public static ArrayNode toJsonArray(@NonNull List<AttachmentInfo> attachments) {
        ArrayNode array = JsonUtils.createArrayNode();
        for (var info : attachments) {
            String mime = StringUtils.isNotBlank(info.mimeType()) ? info.mimeType() : TIKA.detect(info.fileName());
            ObjectNode item = array.addObject();
            item.put("type", info.entityType().getValue());
            item.put("id", info.entityId().toString());
            item.put("file_name", info.fileName());
            item.put("mime_type", mime);
            item.put("media_type", MediaCategory.fromMimeType(mime).name().toLowerCase());
        }
        return array;
    }
}
