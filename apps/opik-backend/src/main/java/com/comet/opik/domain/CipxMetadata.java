package com.comet.opik.domain;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.experimental.UtilityClass;

/**
 * cipx lives under {@code metadata.cipx}: per-call spend on each LLM-call span
 * ({@code cipx.call} + {@code cipx.blocks}) and session identity on the trace
 * ({@code cipx.session.identity}). These gates let the ingestion path skip the vast majority of
 * spans/traces that carry no cipx data before doing any work.
 */
@UtilityClass
public class CipxMetadata {

    public boolean hasSpendCall(JsonNode metadata) {
        return metadata != null && metadata.path("cipx").path("call").isObject();
    }

    public boolean hasIdentity(JsonNode metadata) {
        return metadata != null && metadata.path("cipx").path("session").path("identity").isObject();
    }
}
