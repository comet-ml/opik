package com.comet.opik.infrastructure.db;

import com.comet.opik.domain.topics.PrototypeVectors;
import com.comet.opik.domain.topics.TopicEmbedding;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * The vector BLOB format of {@code topics.vectors} and {@code topic_projects.other_vectors}: the vectors one
 * after another, each {@link TopicEmbedding#DIMENSIONS} little-endian float32 values ({@link TopicEmbedding#BYTES}
 * bytes). The tables' CHECK constraints hold the same length rule.
 */
@UtilityClass
class PrototypeVectorsCodec {

    static byte[] encode(@NonNull PrototypeVectors value) {
        var buffer = ByteBuffer.allocate(value.size() * TopicEmbedding.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        var floats = buffer.asFloatBuffer();
        value.vectors().forEach(floats::put);
        return buffer.array();
    }

    static PrototypeVectors decode(byte @NonNull [] bytes) {
        if (bytes.length == 0 || bytes.length % TopicEmbedding.BYTES != 0) {
            throw new IllegalArgumentException("a vector BLOB has %d bytes, expected a positive multiple of %d"
                    .formatted(bytes.length, TopicEmbedding.BYTES));
        }
        FloatBuffer floats = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();
        List<float[]> vectors = new ArrayList<>(bytes.length / TopicEmbedding.BYTES);
        while (floats.hasRemaining()) {
            var vector = new float[TopicEmbedding.DIMENSIONS];
            floats.get(vector);
            vectors.add(vector);
        }
        return new PrototypeVectors(vectors);
    }
}
