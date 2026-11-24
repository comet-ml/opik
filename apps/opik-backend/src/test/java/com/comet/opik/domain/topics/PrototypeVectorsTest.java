package com.comet.opik.domain.topics;

import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("prototype vectors")
class PrototypeVectorsTest {

    @Test
    @DisplayName("a prototype is a unit vector only with 384 values and unit length; NaN and infinity fail")
    void isUnit() {
        // The embedding model's output size, independent of the constant the code reads.
        assertThat(TopicEmbedding.DIMENSIONS).isEqualTo(384);
        var unit = new float[TopicEmbedding.DIMENSIONS];
        unit[0] = 0.6f;
        unit[1] = 0.8f;
        assertThat(PrototypeVectors.isUnit(unit)).isTrue();
        var nearUnit = unit.clone();
        nearUnit[0] *= 1.0005f;
        assertThat(PrototypeVectors.isUnit(nearUnit)).isTrue();

        var tooLong = unit.clone();
        tooLong[0] = 0.7f;
        assertThat(PrototypeVectors.isUnit(tooLong)).isFalse();
        var nan = unit.clone();
        nan[2] = Float.NaN;
        assertThat(PrototypeVectors.isUnit(nan)).isFalse();
        var infinite = unit.clone();
        infinite[2] = Float.POSITIVE_INFINITY;
        assertThat(PrototypeVectors.isUnit(infinite)).isFalse();
        assertThat(PrototypeVectors.isUnit(new float[]{0.6f, 0.8f})).isFalse();
    }

    @Test
    @DisplayName("a new topic and a first list can only be built from unit prototypes, and a topic from at least one")
    void constructorsRejectInvalidPrototypes() {
        var unit = new float[TopicEmbedding.DIMENSIONS];
        unit[0] = 1;
        var notUnit = unit.clone();
        notUnit[1] = 1;

        assertThat(NewTopic.builder().name("n").definition("d").prototypes(List.of(unit)).build().prototypes())
                .hasSize(1);
        assertThatThrownBy(() -> NewTopic.builder().name("n").definition("d").prototypes(List.of(unit, notUnit))
                .build()).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("prototype vector '1'");
        assertThatThrownBy(() -> NewTopic.builder().name("n").definition("d").prototypes(List.of()).build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owns no prototype");
        assertThatThrownBy(() -> FirstBuild.builder().topics(List.of()).labels(new int[0])
                .otherPrototypes(List.of(new float[]{1f})).stats(JsonUtils.createObjectNode()).build())
                .isInstanceOf(IllegalArgumentException.class);
    }
}
