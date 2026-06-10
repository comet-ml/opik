package com.comet.opik.api.resources.v1.events.tools;

/**
 * Marker interface for bespoke compressors — each announces the
 * {@link EntityType} it handles. {@code GenericCompressor} does not implement
 * this interface; it is polymorphic on the JSON, not on a specific entity
 * shape, and is registered separately.
 *
 * <p>The {@code compress} method itself is not declared here because each
 * bespoke compressor needs its own auxiliary inputs (a trace needs spans, a
 * dataset needs sample items). {@code ReadTool} dispatches per-type rather
 * than uniformly through this interface.
 */
public interface EntityCompressor {
    EntityType type();
}