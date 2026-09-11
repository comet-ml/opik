package com.comet.opik.utils;

import com.comet.opik.api.SpanBatch;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("JsonUtils maxDocumentLength wiring")
class JsonUtilsDocumentLengthTest {

    private static final int STRING_LEN = 100 * 1024 * 1024; // 100MB, generous

    // Jackson enforces maxDocumentLength inside _loadMore() (buffer refill), so it only triggers when
    // the parser reads across buffer boundaries from a stream - i.e. an InputStream/Reader, not a
    // fully-in-memory byte[]/String (which fits the initial buffer and never refills). The readTree
    // cases below verify that wiring on the stream path; typedBeanBinding_wrapsStreamConstraint covers
    // the real ingestion path, where binding into a typed bean wraps the exception.
    private static ByteArrayInputStream manySmallValues(int minLength) {
        StringBuilder sb = new StringBuilder("{");
        int i = 0;
        while (sb.length() < minLength) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"k").append(i).append("\":").append(i);
            i++;
        }
        return new ByteArrayInputStream(sb.append('}').toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("a document over maxDocumentLength is rejected mid-parse (StreamConstraintsException -> 413)")
    void overMaxDocumentLength_rejected() {
        ObjectMapper mapper = JsonUtils.createConfiguredMapper(STRING_LEN, 1024L); // 1KB

        // ~200KB of tiny values, streamed - forces buffer refills past the 1KB limit
        assertThatThrownBy(() -> mapper.readTree(manySmallValues(200 * 1024)))
                .isInstanceOf(StreamConstraintsException.class);
    }

    @Test
    @DisplayName("a document under maxDocumentLength parses fine")
    void underMaxDocumentLength_parses() {
        ObjectMapper mapper = JsonUtils.createConfiguredMapper(STRING_LEN, 1024 * 1024L); // 1MB

        assertThatCode(() -> {
            JsonNode node = mapper.readTree(
                    new ByteArrayInputStream("{\"a\":1,\"b\":\"x\"}".getBytes(StandardCharsets.UTF_8)));
            assertThat(node.get("a").asInt()).isEqualTo(1);
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("maxDocumentLength <= 0 leaves the document unbounded")
    void unlimitedDocumentLength_parsesLargeDocument() {
        ObjectMapper mapper = JsonUtils.createConfiguredMapper(STRING_LEN, -1L);

        assertThatCode(() -> mapper.readTree(manySmallValues(200 * 1024))).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("maxStringLength still bounds a single oversized value even when the document fits")
    void maxStringLength_stillEnforced() {
        int smallStringLimit = 1024 * 1024; // 1MB
        ObjectMapper mapper = JsonUtils.createConfiguredMapper(smallStringLimit, -1L);

        ByteArrayInputStream oneHugeString = new ByteArrayInputStream(
                ("{\"v\":\"" + "a".repeat(smallStringLimit + 1024) + "\"}").getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> mapper.readTree(oneHugeString))
                .isInstanceOf(StreamConstraintsException.class);
    }

    // {"spans":[{"output":{<many small values>}}]} - big enough that maxDocumentLength trips while the
    // parser is reading Span["output"], so Jackson wraps the StreamConstraintsException in a
    // JsonMappingException (the real ingestion path, unlike the untyped readTree cases above).
    private static ByteArrayInputStream spanBatchWithBigOutput(int minBytes) {
        StringBuilder sb = new StringBuilder("{\"spans\":[{\"output\":{");
        int i = 0;
        while (sb.length() < minBytes) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append("\"k").append(i).append("\":").append(i);
            i++;
        }
        sb.append("}}]}");
        return new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("binding into a typed bean wraps the size trip in a JsonMappingException (real ingestion path)")
    void typedBeanBinding_wrapsStreamConstraint() {
        ObjectMapper mapper = JsonUtils.createConfiguredMapper(STRING_LEN, 1024L); // 1KB doc cap

        // Unlike the untyped readTree cases above, deserializing into a typed bean makes Jackson re-wrap
        // the parser-level StreamConstraintsException in a JsonMappingException (with the reference
        // chain) - exactly what prod hits on /spans/batch and what the size-guard metric fix unwraps.
        assertThatThrownBy(() -> mapper.readValue(spanBatchWithBigOutput(200 * 1024), SpanBatch.class))
                .isInstanceOf(JsonMappingException.class)
                .hasRootCauseInstanceOf(StreamConstraintsException.class);
    }
}
