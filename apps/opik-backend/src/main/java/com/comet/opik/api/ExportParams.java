package com.comet.opik.api;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import org.apache.commons.codec.digest.DigestUtils;

/**
 * Everything the export worker needs to produce a file, shaped per export type.
 *
 * <p>Persisted as the opaque {@code params} JSON column on {@code export_jobs}. Deliberately not sealed and
 * deliberately without a {@code @JsonSubTypes} list: implementations are registered at startup from the bound
 * {@code ExportSource}s, so a new exportable surface adds a record and a binding rather than editing this file.</p>
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "export_type", visible = true)
public interface ExportParams {

    /**
     * Stable identifier for this export type, matching the {@code ExportSource} that handles it. Persisted, so it
     * must not change once rows exist.
     */
    @JsonIgnore
    String exportType();

    /**
     * Stable identity of the rows this job will produce, used to dedupe concurrent requests for the same export.
     * Implementations canonicalise their own contents so that logically equal params hash equally.
     */
    @JsonIgnore
    default String canonicalHash() {
        return DigestUtils.sha256Hex(JsonUtils.writeValueAsBytes(this));
    }
}
