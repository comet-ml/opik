package com.comet.opik.domain.ailabels;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class AiLabelTaskTest {

    private static final Instant V1 = Instant.parse("2026-10-20T00:00:00Z");
    private static final Instant V2 = Instant.parse("2026-11-01T00:00:00Z");
    private static final Instant V3_RELABEL = Instant.parse("2026-10-25T00:00:00Z");

    /** v1 from 10-20, v2 from 11-01. */
    private static final AiLabelTask TWO_VERSIONS = AiLabelTaskFixtures.generate("summary", false).toBuilder()
            .version(2)
            .appliesFrom(V2)
            .history(List.of(new AiLabelTask.Version(1, V1)))
            .build();

    /** v3 relabels from 10-25, earlier than v2's 11-01. */
    private static final AiLabelTask RELABEL = TWO_VERSIONS.toBuilder()
            .version(3)
            .appliesFrom(V3_RELABEL)
            .history(List.of(new AiLabelTask.Version(1, V1), new AiLabelTask.Version(2, V2)))
            .build();

    static Stream<Arguments> requiredVersions() {
        return Stream.of(
                arguments("before every version", TWO_VERSIONS, V1.minusNanos(1000), 0),
                arguments("at v1's applies_from", TWO_VERSIONS, V1, 1),
                arguments("between v1 and v2", TWO_VERSIONS, V2.minusNanos(1000), 1),
                arguments("at v2's applies_from", TWO_VERSIONS, V2, 2),
                arguments("after v2", TWO_VERSIONS, V2.plusSeconds(3600), 2),
                arguments("relabel: before v1", RELABEL, V1.minusSeconds(1), 0),
                arguments("relabel: between v1 and the relabel", RELABEL, V3_RELABEL.minusNanos(1000), 1),
                arguments("relabel: at the relabel's applies_from", RELABEL, V3_RELABEL, 3),
                arguments("relabel: after v2's applies_from", RELABEL, V2.plusSeconds(1), 3));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("requiredVersions")
    void requiredIsTheHighestVersionAppliedAtCreation(String description, AiLabelTask task, Instant createdAt,
            int expected) {
        assertThat(task.required(createdAt)).isEqualTo(expected);
    }
}
