package com.comet.opik.infrastructure.metrics;

import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.comet.opik.infrastructure.metrics.IngestionSizeGuardMetrics.GUARD_DOCUMENT_LENGTH;
import static com.comet.opik.infrastructure.metrics.IngestionSizeGuardMetrics.GUARD_STREAM_CONSTRAINT;
import static com.comet.opik.infrastructure.metrics.IngestionSizeGuardMetrics.GUARD_STRING_LENGTH;
import static com.comet.opik.infrastructure.metrics.IngestionSizeGuardMetrics.classifyStreamConstraint;
import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("IngestionSizeGuardMetrics.classifyStreamConstraint")
class IngestionSizeGuardMetricsTest {

    @Test
    @DisplayName("document-length overrun maps to document_length")
    void documentLength() {
        var exception = new StreamConstraintsException(
                "Document length (300000000) exceeds the maximum allowed (268435456, from "
                        + "`StreamReadConstraints.getMaxDocumentLength()`)");

        assertThat(classifyStreamConstraint(exception)).isEqualTo(GUARD_DOCUMENT_LENGTH);
    }

    @Test
    @DisplayName("string-length overrun maps to string_length")
    void stringLength() {
        var exception = new StreamConstraintsException(
                "String value length (200000000) exceeds the maximum allowed (104857600, from "
                        + "`StreamReadConstraints.getMaxStringLength()`)");

        assertThat(classifyStreamConstraint(exception)).isEqualTo(GUARD_STRING_LENGTH);
    }

    @Test
    @DisplayName("an unrecognized constraint message falls back to stream_constraint rather than guessing")
    void unknownMessage() {
        assertThat(classifyStreamConstraint(new StreamConstraintsException("Number value length exceeds limit")))
                .isEqualTo(GUARD_STREAM_CONSTRAINT);
    }

    @Test
    @DisplayName("a null message falls back to stream_constraint")
    void nullMessage() {
        assertThat(classifyStreamConstraint(new StreamConstraintsException(null)))
                .isEqualTo(GUARD_STREAM_CONSTRAINT);
    }
}
