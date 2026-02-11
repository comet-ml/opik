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

    @Test
    void testGenAiConversationIdMappingRule() {
        // Test that gen_ai.conversation.id is mapped to thread_id in metadata
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.conversation.id")
                        .setValue(AnyValue.newBuilder().setStringValue("conversation-456"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        // Verify gen_ai.conversation.id is stored as thread_id in metadata
        assertThat(span.metadata().has("thread_id")).isTrue();
        assertThat(span.metadata().get("thread_id").asText()).isEqualTo("conversation-456");
    }

    @Test
    void testThreadIdTakesPrecedenceOverConversationId() {
        // Test that thread_id takes precedence when both are present
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("thread_id")
                        .setValue(AnyValue.newBuilder().setStringValue("thread-wins"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.conversation.id")
                        .setValue(AnyValue.newBuilder().setStringValue("conversation-loses"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        // First one wins - thread_id should take precedence
        assertThat(span.metadata().has("thread_id")).isTrue();
        assertThat(span.metadata().get("thread_id").asText()).isEqualTo("thread-wins");
    }

    @Test
    void testThreadIdWithIntegerValue() {
        // Test that thread_id works when sent as an integer value (per OpenTelemetry thread.id spec)
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("thread_id")
                        .setValue(AnyValue.newBuilder().setIntValue(12345))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        // Verify integer thread_id is stored in metadata
        assertThat(span.metadata().has("thread_id")).isTrue();
        assertThat(span.metadata().get("thread_id").asLong()).isEqualTo(12345L);
    }

    @Test
    void testOpenInferenceAttributesMapping() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("llm.model_name")
                        .setValue(AnyValue.newBuilder().setStringValue("gpt-4o"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("llm.provider")
                        .setValue(AnyValue.newBuilder().setStringValue("openai"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("llm.token_count.prompt")
                        .setValue(AnyValue.newBuilder().setIntValue(12))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("llm.output_messages.0.content")
                        .setValue(AnyValue.newBuilder().setStringValue("hello"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("input.value")
                        .setValue(AnyValue.newBuilder().setStringValue("request"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("output.value")
                        .setValue(AnyValue.newBuilder().setStringValue("response"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();
        assertThat(span.model()).isEqualTo("gpt-4o");
        assertThat(span.provider()).isEqualTo("openai");
        assertThat(span.usage()).containsEntry("prompt", 12);
        assertThat(span.input().get("input.value").asText()).isEqualTo("request");
        assertThat(span.output().get("output.value").asText()).isEqualTo("response");
        assertThat(span.output().get("llm.output_messages.0.content").asText()).isEqualTo("hello");
    }
}
