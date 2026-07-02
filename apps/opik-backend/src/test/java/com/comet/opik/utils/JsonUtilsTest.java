package com.comet.opik.utils;

import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JsonUtils stream read constraints")
class JsonUtilsTest {

    private static final int MAX_DOCUMENT_LENGTH = 1024 * 1024;

    private ObjectMapper mapperWithDocumentLimit() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.getFactory().setStreamReadConstraints(StreamReadConstraints.builder()
                .maxStringLength(StreamReadConstraints.DEFAULT_MAX_STRING_LEN)
                .maxDocumentLength(MAX_DOCUMENT_LENGTH)
                .build());
        return mapper;
    }

    private String documentOfManySmallNodes(int targetBytes) {
        var builder = new StringBuilder("[");
        // Each entry is a small object like {"k":12345}, ~12 bytes — none individually large, so
        // maxStringLength never trips; only the total document length does.
        while (builder.length() < targetBytes) {
            builder.append("{\"k\":12345},");
        }
        builder.setCharAt(builder.length() - 1, ']');
        return builder.toString();
    }

    private ByteArrayInputStream asStream(String json) {
        return new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("rejects a document of many small nodes exceeding maxDocumentLength")
    void rejectsLargeDocumentOfManySmallNodes() {
        var mapper = mapperWithDocumentLimit();
        // Read from a stream, matching the production HTTP path: maxDocumentLength is enforced as the
        // parser loads input buffers, so the oversized tree is never fully materialized — the parse
        // aborts after reading ~limit + one buffer, regardless of how large the full document is.
        var oversized = asStream(documentOfManySmallNodes(2 * MAX_DOCUMENT_LENGTH));

        assertThatThrownBy(() -> mapper.readTree(oversized))
                .isInstanceOf(StreamConstraintsException.class)
                .hasMessageContaining("Document length");
    }

    @Test
    @DisplayName("accepts a small-node document under maxDocumentLength")
    void acceptsDocumentUnderLimit() {
        var mapper = mapperWithDocumentLimit();
        var underLimit = asStream(documentOfManySmallNodes(MAX_DOCUMENT_LENGTH / 2));

        assertThatCode(() -> {
            var node = mapper.readTree(underLimit);
            assertThat(node.isArray()).isTrue();
        }).doesNotThrowAnyException();
    }
}
