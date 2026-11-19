package com.comet.opik.infrastructure.db;

import com.comet.opik.domain.topics.PrototypeVectors;
import com.comet.opik.domain.topics.TopicEmbedding;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class PrototypeVectorsCodecTest {

    private static float[] vector(float seed) {
        var vector = new float[TopicEmbedding.DIMENSIONS];
        for (int i = 0; i < vector.length; i++) {
            vector[i] = seed + i * 1e-3f;
        }
        return vector;
    }

    @Test
    @DisplayName("vectors round-trip bit for bit and keep their order")
    void roundTrip() {
        var vectors = new PrototypeVectors(List.of(vector(0.5f), vector(-0.25f), vector(Float.MIN_VALUE)));

        byte[] bytes = PrototypeVectorsCodec.encode(vectors);

        assertThat(bytes).hasSize(3 * TopicEmbedding.BYTES);
        assertThat(PrototypeVectorsCodec.decode(bytes)).isEqualTo(vectors);
    }

    @Test
    @DisplayName("the BLOB is little-endian float32, vector after vector")
    void layout() {
        var vectors = new PrototypeVectors(List.of(vector(0.5f), vector(2f)));

        var floats = ByteBuffer.wrap(PrototypeVectorsCodec.encode(vectors)).order(ByteOrder.LITTLE_ENDIAN);

        assertThat(floats.getFloat(0)).isEqualTo(0.5f);
        assertThat(floats.getFloat(TopicEmbedding.BYTES)).isEqualTo(2f);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 4, TopicEmbedding.BYTES - 4, TopicEmbedding.BYTES + 4})
    @DisplayName("a BLOB that is empty or not a whole number of vectors is refused")
    void wrongLength(int length) {
        assertThatThrownBy(() -> PrototypeVectorsCodec.decode(new byte[length]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> invalidVectors() {
        return Stream.of(
                arguments("empty", List.of()),
                arguments("too short", List.of(new float[TopicEmbedding.DIMENSIONS - 1])),
                arguments("too long", List.of(vector(0f), new float[TopicEmbedding.DIMENSIONS + 1])),
                arguments("null vector", Arrays.asList(vector(0f), null)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("prototype vectors must be non-empty, non-null and of the embedding's dimensions")
    void invalidVectors(String name, List<float[]> vectors) {
        assertThatThrownBy(() -> new PrototypeVectors(vectors))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("prototype vectors copy their arrays, so a later write to the input does not change them")
    void defensiveCopy() {
        var input = vector(1f);
        var vectors = new PrototypeVectors(List.of(input));

        input[0] = 42f;

        assertThat(vectors).isEqualTo(new PrototypeVectors(List.of(vector(1f))));
    }
}
