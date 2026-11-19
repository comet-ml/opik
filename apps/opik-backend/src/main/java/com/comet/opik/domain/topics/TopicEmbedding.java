package com.comet.opik.domain.topics;

import lombok.experimental.UtilityClass;

/**
 * The embedding every topics vector comes from. opik-inference picks the model, so this is what the backend expects
 * it to serve, not a setting: a model change re-embeds every trace and rebuilds every list, so it ships together with
 * opik-inference. Stored next to each vector set, so a reader can tell a set from another model.
 */
@UtilityClass
public class TopicEmbedding {

    public static final String MODEL = "bge-small-en-v1.5";

    public static final int DIMENSIONS = 384;

    /** The bytes of one vector in a MySQL vector BLOB: {@link #DIMENSIONS} little-endian float32 values. */
    public static final int BYTES = DIMENSIONS * Float.BYTES;
}
