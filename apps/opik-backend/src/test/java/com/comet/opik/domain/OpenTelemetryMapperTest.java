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
    void testGenAiOutputMessagesMapsToOutput() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.output.messages")
                        .setValue(AnyValue.newBuilder()
                                .setStringValue("[{\"role\":\"assistant\",\"content\":\"Hello\"}]"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.output().has("gen_ai.output.messages")).isTrue();
        assertThat(span.input()).isNull();
    }

    @Test
    void testGenAiInputMessagesMapsToInput() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.input.messages")
                        .setValue(AnyValue.newBuilder().setStringValue("[{\"role\":\"user\",\"content\":\"Hi\"}]"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.input().has("gen_ai.input.messages")).isTrue();
    }

    @Test
    void testGenAiSystemInstructionsMapsToInput() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.system_instructions")
                        .setValue(AnyValue.newBuilder().setStringValue("You are a helpful assistant"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.input().has("gen_ai.system_instructions")).isTrue();
    }

    @Test
    void testGenAiCostAttributesMapsToMetadata() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.cost.total_cost")
                        .setValue(AnyValue.newBuilder().setDoubleValue(0.0035))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.cost.input_cost")
                        .setValue(AnyValue.newBuilder().setDoubleValue(0.001))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.cost.output_cost")
                        .setValue(AnyValue.newBuilder().setDoubleValue(0.0025))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.metadata().has("gen_ai.cost.total_cost")).isTrue();
        assertThat(span.metadata().has("gen_ai.cost.input_cost")).isTrue();
        assertThat(span.metadata().has("gen_ai.cost.output_cost")).isTrue();
        assertThat(span.input()).isNull();
    }

    @Test
    void testOldAndNewGenAiAttributesCoexist() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.prompt")
                        .setValue(AnyValue.newBuilder().setStringValue("old prompt"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.input.messages")
                        .setValue(AnyValue.newBuilder().setStringValue("[{\"role\":\"user\",\"content\":\"new\"}]"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.completion")
                        .setValue(AnyValue.newBuilder().setStringValue("old completion"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.output.messages")
                        .setValue(
                                AnyValue.newBuilder().setStringValue("[{\"role\":\"assistant\",\"content\":\"new\"}]"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.input().has("gen_ai.prompt")).isTrue();
        assertThat(span.input().has("gen_ai.input.messages")).isTrue();
        assertThat(span.output().has("gen_ai.completion")).isTrue();
        assertThat(span.output().has("gen_ai.output.messages")).isTrue();
    }

    @Test
    void testGenAiToolAttributesMapsToMetadata() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.tool.name")
                        .setValue(AnyValue.newBuilder().setStringValue("get_weather"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.tool.call.id")
                        .setValue(AnyValue.newBuilder().setStringValue("call_abc123"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.metadata().has("gen_ai.tool.name")).isTrue();
        assertThat(span.metadata().has("gen_ai.tool.call.id")).isTrue();
    }

    @Test
    void testLlmIsStreamingMapsToMetadata() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("llm.is_streaming")
                        .setValue(AnyValue.newBuilder().setBoolValue(true))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.metadata().has("llm.is_streaming")).isTrue();
    }

    @Test
    void testLlmResponseAttributesMapsToMetadata() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("llm.response.finish_reason")
                        .setValue(AnyValue.newBuilder().setStringValue("stop"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.metadata().has("llm.response.finish_reason")).isTrue();
        assertThat(span.input()).isNull();
    }

    @Test
    void testExistingGenAiRulesUnaffected() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.request.model")
                        .setValue(AnyValue.newBuilder().setStringValue("gpt-4"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.response.model")
                        .setValue(AnyValue.newBuilder().setStringValue("gpt-4-0613"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.usage.input_tokens")
                        .setValue(AnyValue.newBuilder().setIntValue(100))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.usage.output_tokens")
                        .setValue(AnyValue.newBuilder().setIntValue(50))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.system")
                        .setValue(AnyValue.newBuilder().setStringValue("openai"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.response.id")
                        .setValue(AnyValue.newBuilder().setStringValue("chatcmpl-abc"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.response.finish_reasons")
                        .setValue(AnyValue.newBuilder().setStringValue("[\"stop\"]"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.operation.name")
                        .setValue(AnyValue.newBuilder().setStringValue("chat"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.conversation.id")
                        .setValue(AnyValue.newBuilder().setStringValue("conv-123"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        // MODEL: response.model overwrites request.model (last-write-wins)
        assertThat(span.model()).isEqualTo("gpt-4-0613");

        // USAGE: input_tokens→prompt_tokens, output_tokens→completion_tokens (key mapping)
        assertThat(span.usage().get("prompt_tokens")).isNotNull();
        assertThat(span.usage().get("completion_tokens")).isNotNull();

        // PROVIDER: gen_ai.system
        assertThat(span.provider()).isEqualTo("openai");

        // METADATA: response.id, response.finish_reasons, operation.name
        assertThat(span.metadata().has("gen_ai.response.id")).isTrue();
        assertThat(span.metadata().has("gen_ai.response.finish_reasons")).isTrue();
        assertThat(span.metadata().has("gen_ai.operation.name")).isTrue();

        // THREAD_ID: conversation.id
        assertThat(span.metadata().has("thread_id")).isTrue();
        assertThat(span.metadata().get("thread_id").asText()).isEqualTo("conv-123");
    }

    @Test
    void testLiteLLMMetadataAttributesMapsToMetadata() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("metadata.user_api_key_hash")
                        .setValue(AnyValue.newBuilder().setStringValue("sk-hash-123"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("metadata.user_api_key_team_id")
                        .setValue(AnyValue.newBuilder().setStringValue("team-456"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("metadata.requester_ip_address")
                        .setValue(AnyValue.newBuilder().setStringValue("127.0.0.1"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.metadata().has("metadata.user_api_key_hash")).isTrue();
        assertThat(span.metadata().has("metadata.user_api_key_team_id")).isTrue();
        assertThat(span.metadata().has("metadata.requester_ip_address")).isTrue();
        assertThat(span.input()).isNull();
    }

    @Test
    void testLiteLLMHiddenParamsMapsToMetadata() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("hidden_params")
                        .setValue(AnyValue.newBuilder().setStringValue(
                                "{\"api_base\":\"https://api.openai.com\",\"response_cost\":0.005}"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.metadata().has("hidden_params")).isTrue();
        assertThat(span.input()).isNull();
    }
}
