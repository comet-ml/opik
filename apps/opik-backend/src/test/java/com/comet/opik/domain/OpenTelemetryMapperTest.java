package com.comet.opik.domain;

import com.comet.opik.api.Span;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OpenTelemetryMapperTest {

    @Test
    void testThreadIdMappingRule() {
        // Test that the thread_id attribute is mapped to metadata
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("thread_id")
                        .setValue(AnyValue.newBuilder().setStringValue("test-thread-123"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        // Verify thread_id is stored in metadata
        assertThat(span.metadata().has("thread_id")).isTrue();
        assertThat(span.metadata().get("thread_id").asText()).isEqualTo("test-thread-123");
    }
}
