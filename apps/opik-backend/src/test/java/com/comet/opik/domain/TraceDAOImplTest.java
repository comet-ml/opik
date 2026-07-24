package com.comet.opik.domain;

import com.comet.opik.api.Trace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("TraceDAOImpl get-by-id reducer")
class TraceDAOImplTest {

    @Test
    void firstOrLogFanOut__whenNoRows__returnsEmpty() {
        assertThat(TraceDAOImpl.firstOrLogFanOut(List.of(), UUID.randomUUID(), "ws")).isEmpty();
    }

    @Test
    void firstOrLogFanOut__whenSingleRow__returnsIt() {
        var id = UUID.randomUUID();
        var trace = Trace.builder().id(id).build();

        assertThat(TraceDAOImpl.firstOrLogFanOut(List.of(trace), id, "ws")).containsSame(trace);
    }

    @Test
    void firstOrLogFanOut__whenMoreThanOneRow__returnsFirstWithoutThrowing() {
        var id = UUID.randomUUID();
        var first = Trace.builder().id(id).build();
        var second = Trace.builder().id(id).build();

        // Must not throw IndexOutOfBoundsException ("Source emitted more than one item").
        assertThat(TraceDAOImpl.firstOrLogFanOut(List.of(first, second), id, "ws")).containsSame(first);
    }
}
