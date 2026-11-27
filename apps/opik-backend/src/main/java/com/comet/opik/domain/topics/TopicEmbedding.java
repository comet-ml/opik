package com.comet.opik.domain.topics;

import lombok.experimental.UtilityClass;

/**
 * The embedding every topics vector comes from. opik-inference picks the model, so this is what the backend expects
 * it to serve, not a setting: a model change re-embeds every trace and rebuilds every list, so it ships together with
 * opik-inference. Stored next to each vector set, so a reader can tell a set from another model.
 */
@UtilityClass
public class TopicEmbedding {

    /** The ai_labels task whose text is embedded: every {@code topic_vectors} row has it as its {@code task}. */
    public static final String SUMMARY_TASK = "trace_summary";

    public static final String MODEL = "bge-small-en-v1.5";

    public static final int DIMENSIONS = 384;

    /** The bytes of one vector in a MySQL vector BLOB: {@link #DIMENSIONS} little-endian float32 values. */
    public static final int BYTES = DIMENSIONS * Float.BYTES;

    /**
     * Whether a trace's vector can be labelled: the run's window and the labelling pass take the same ones. Length
     * only: the vectors come back from a {@code BFloat16} column, whose cast truncates, so their norm is not 1.
     */
    public static boolean usable(float[] vector) {
        return vector.length == DIMENSIONS;
    }

    /**
     * The vector a window read returns for {@code vector} once it is stored: ClickHouse truncates each value to
     * BFloat16 on insert (it keeps the top 16 bits), and the read scales the result back to unit length
     * ({@link #toUnit}). Arrival labels this copy, so its label is the one the labelling pass gives the stored vector.
     */
    public static float[] stored(float[] vector) {
        var out = new float[vector.length];
        for (int i = 0; i < vector.length; i++) {
            out[i] = Float.intBitsToFloat(Float.floatToRawIntBits(vector[i]) & 0xFFFF0000);
        }
        return toUnit(out);
    }

    /** Scales {@code vector} to unit length in place, and returns it; a zero vector stays as it is. */
    static float[] toUnit(float[] vector) {
        double norm = 0;
        for (float value : vector) {
            norm += (double) value * value;
        }
        if (norm > 0) {
            float scale = (float) (1 / Math.sqrt(norm));
            for (int i = 0; i < vector.length; i++) {
                vector[i] *= scale;
            }
        }
        return vector;
    }
}
