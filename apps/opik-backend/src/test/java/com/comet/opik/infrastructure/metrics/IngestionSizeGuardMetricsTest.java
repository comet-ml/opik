package com.comet.opik.infrastructure.metrics;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static com.comet.opik.infrastructure.metrics.IngestionSizeGuardMetrics.GUARD_DOCUMENT_LENGTH;
import static com.comet.opik.infrastructure.metrics.IngestionSizeGuardMetrics.GUARD_STRING_LENGTH;
import static com.comet.opik.infrastructure.metrics.IngestionSizeGuardMetrics.GUARD_UNCLASSIFIED;
import static com.comet.opik.infrastructure.metrics.IngestionSizeGuardMetrics.classifyStreamConstraint;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

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
    @DisplayName("an unrecognized constraint message falls back to unclassified rather than guessing")
    void unknownMessage() {
        assertThat(classifyStreamConstraint(new StreamConstraintsException("Number value length exceeds limit")))
                .isEqualTo(GUARD_UNCLASSIFIED);
    }

    @Test
    @DisplayName("a null message falls back to unclassified")
    void nullMessage() {
        assertThat(classifyStreamConstraint(new StreamConstraintsException(null)))
                .isEqualTo(GUARD_UNCLASSIFIED);
    }

    // Real-parse tests: drive actual Jackson past the caps so a future Jackson message reword breaks
    // these instead of silently collapsing the dashboard breakdown to unclassified (see the note
    // on classifyStreamConstraint).

    @Test
    @DisplayName("real Jackson document-length overrun classifies as document_length")
    void realDocumentLengthOverrun() {
        ObjectMapper mapper = new ObjectMapper();
        JsonUtils.applyStreamReadConstraints(mapper, 100 * 1024 * 1024, 1024L); // 1KB doc cap
        StringBuilder doc = new StringBuilder("{");
        for (int i = 0; doc.length() < 200 * 1024; i++) {
            if (i > 0) {
                doc.append(',');
            }
            doc.append("\"k").append(i).append("\":").append(i);
        }
        doc.append('}');

        Throwable thrown = catchThrowable(
                () -> mapper.readTree(new ByteArrayInputStream(doc.toString().getBytes(StandardCharsets.UTF_8))));

        assertThat(thrown).isInstanceOf(StreamConstraintsException.class);
        assertThat(classifyStreamConstraint((StreamConstraintsException) thrown)).isEqualTo(GUARD_DOCUMENT_LENGTH);
    }

    @Test
    @DisplayName("real Jackson string-length overrun classifies as string_length")
    void realStringLengthOverrun() {
        ObjectMapper mapper = new ObjectMapper();
        JsonUtils.applyStreamReadConstraints(mapper, 1024, -1L); // 1KB string cap, unlimited doc
        String oneHugeString = "{\"v\":\"" + "a".repeat(2048) + "\"}";

        Throwable thrown = catchThrowable(
                () -> mapper.readTree(new ByteArrayInputStream(oneHugeString.getBytes(StandardCharsets.UTF_8))));

        assertThat(thrown).isInstanceOf(StreamConstraintsException.class);
        assertThat(classifyStreamConstraint((StreamConstraintsException) thrown)).isEqualTo(GUARD_STRING_LENGTH);
    }
}
