package com.comet.opik.api.resources.v1.events.tools;

/**
 * Size-class bucket for compressed entity output. Each entity type defines what
 * each applicable tier actually contains — the tier label communicates "how
 * reduced" the payload is, not a one-size-fits-all schema.
 */
public enum CompressionTier {
    FULL,
    MEDIUM,
    SKELETON,
    SUMMARY
}