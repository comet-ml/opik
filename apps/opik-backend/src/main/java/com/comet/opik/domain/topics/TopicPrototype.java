package com.comet.opik.domain.topics;

import com.google.common.base.Preconditions;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;

/**
 * One row of {@code topic_prototypes}: a unit-length vector that assigns traces to {@code topicId}, or to Other when it
 * is null (a "known Other" prototype). {@code position} is its index in the project's set, the tie-break of the
 * assignment.
 */
@Builder(toBuilder = true)
public record TopicPrototype(
        @NonNull UUID id,
        @NonNull String workspaceId,
        @NonNull UUID projectId,
        int position,
        @Nullable UUID topicId,
        @NonNull byte[] vector) {

    /** {@code values} as stored: little-endian float32. */
    public static byte[] encode(@NonNull float[] values) {
        var buffer = ByteBuffer.allocate(values.length * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        buffer.asFloatBuffer().put(values);
        return buffer.array();
    }

    public static float[] decode(@NonNull byte[] bytes) {
        Preconditions.checkArgument(bytes.length % Float.BYTES == 0,
                "a prototype vector must be whole float32 values, bytes '%s'", bytes.length);
        var values = new float[bytes.length / Float.BYTES];
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(values);
        return values;
    }

    public float[] values() {
        return decode(vector);
    }
}
