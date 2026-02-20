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
    void testThreadIdTakesPrecedenceOverConversationIdWhenOrderedSecond() {
        // Test that thread_id still wins when conversation id is encountered first
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.conversation.id")
                        .setValue(AnyValue.newBuilder().setStringValue("conversation-first"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("thread_id")
                        .setValue(AnyValue.newBuilder().setStringValue("thread-second"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.metadata().has("thread_id")).isTrue();
        assertThat(span.metadata().get("thread_id").asText()).isEqualTo("thread-second");
    }

    @Test
    void testMalformedUsageJsonDoesNotBreakSpanMapping() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.usage.input_tokens")
                        .setValue(AnyValue.newBuilder().setIntValue(100).build())
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.usage.invalid")
                        .setValue(AnyValue.newBuilder()
                                .setStringValue("not a valid integer and not valid json {")
                                .build())
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.output.messages")
                        .setValue(AnyValue.newBuilder().setStringValue("[{\"role\":\"assistant\",\"content\":\"ok\"}]")
                                .build())
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.usage().get("prompt_tokens")).isEqualTo(100);
        assertThat(span.output().has("gen_ai.output.messages")).isTrue();
    }

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
    void testGenAiProviderNameMapsToProvider() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.provider.name")
                        .setValue(AnyValue.newBuilder().setStringValue("openai"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.provider()).isEqualTo("openai");
        assertThat(span.metadata()).isNull();
    }

    @Test
    void testGenAiRetrievalAttributesMapToInput() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.retrieval.query.text")
                        .setValue(AnyValue.newBuilder().setStringValue("weather forecast"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.retrieval.documents")
                        .setValue(AnyValue.newBuilder().setStringValue(
                                "[{\"id\":\"doc_1\",\"score\":0.97},{\"id\":\"doc_2\",\"score\":0.91}]"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.input().has("gen_ai.retrieval.query.text")).isTrue();
        assertThat(span.input().has("gen_ai.retrieval.documents")).isTrue();
        assertThat(span.output()).isNull();
    }

    @Test
    void testGenAiRequestControlPlaneAttributesMapToMetadata() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.request.max_tokens")
                        .setValue(AnyValue.newBuilder().setIntValue(256))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.request.top_p")
                        .setValue(AnyValue.newBuilder().setDoubleValue(0.9))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.request.top_k")
                        .setValue(AnyValue.newBuilder().setIntValue(40))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.request.stop_sequences")
                        .setValue(AnyValue.newBuilder().setStringValue("[\"END\"]"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.response.stop_reason")
                        .setValue(AnyValue.newBuilder().setStringValue("stop"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.request.presence_penalty")
                        .setValue(AnyValue.newBuilder().setDoubleValue(0.1))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.request.frequency_penalty")
                        .setValue(AnyValue.newBuilder().setDoubleValue(-0.1))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.request.seed")
                        .setValue(AnyValue.newBuilder().setIntValue(123))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.request.choice.count")
                        .setValue(AnyValue.newBuilder().setIntValue(3))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.request.encoding_formats")
                        .setValue(AnyValue.newBuilder().setStringValue("[\"json\"]"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.metadata().has("gen_ai.request.max_tokens")).isTrue();
        assertThat(span.metadata().has("gen_ai.request.top_p")).isTrue();
        assertThat(span.metadata().has("gen_ai.request.top_k")).isTrue();
        assertThat(span.metadata().has("gen_ai.request.stop_sequences")).isTrue();
        assertThat(span.metadata().has("gen_ai.response.stop_reason")).isTrue();
        assertThat(span.metadata().has("gen_ai.request.presence_penalty")).isTrue();
        assertThat(span.metadata().has("gen_ai.request.frequency_penalty")).isTrue();
        assertThat(span.metadata().has("gen_ai.request.seed")).isTrue();
        assertThat(span.metadata().has("gen_ai.request.choice.count")).isTrue();
        assertThat(span.metadata().has("gen_ai.request.encoding_formats")).isTrue();
        assertThat(span.input()).isNull();
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
    void testOpenAiApiAttributesMapToMetadata() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("openai.api.type")
                        .setValue(AnyValue.newBuilder().setStringValue("chat_completions"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("openai.request.service_tier")
                        .setValue(AnyValue.newBuilder().setStringValue("default"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("openai.response.service_tier")
                        .setValue(AnyValue.newBuilder().setStringValue("scale"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("openai.response.system_fingerprint")
                        .setValue(AnyValue.newBuilder().setStringValue("fp_44709d6fcb"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.metadata().get("openai.api.type").asText()).isEqualTo("chat_completions");
        assertThat(span.metadata().get("openai.request.service_tier").asText()).isEqualTo("default");
        assertThat(span.metadata().get("openai.response.service_tier").asText()).isEqualTo("scale");
        assertThat(span.metadata().get("openai.response.system_fingerprint").asText())
                .isEqualTo("fp_44709d6fcb");
    }

    @Test
    void testGenAiOutputTypeMapsToMetadata() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.output.type")
                        .setValue(AnyValue.newBuilder().setStringValue("text").build())
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.metadata().has("gen_ai.output.type")).isTrue();
        assertThat(span.metadata().get("gen_ai.output.type").asText()).isEqualTo("text");
        assertThat(span.input()).isNull();
    }

    @Test
    void testGenAiPromptNameMapsToMetadata() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.prompt.name")
                        .setValue(AnyValue.newBuilder().setStringValue("analyze-code").build())
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.metadata().has("gen_ai.prompt.name")).isTrue();
        assertThat(span.metadata().get("gen_ai.prompt.name").asText()).isEqualTo("analyze-code");
        assertThat(span.input()).isNull();
    }

    @Test
    void testGenAiDeprecatedOpenAiRequestResponseFormatMapsToMetadata() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.openai.request.response_format")
                        .setValue(AnyValue.newBuilder().setStringValue("json_object").build())
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.metadata().has("gen_ai.openai.request.response_format")).isTrue();
        assertThat(span.metadata().get("gen_ai.openai.request.response_format").asText()).isEqualTo("json_object");
        assertThat(span.input()).isNull();
    }

    @Test
    void testGenAiDeprecatedOpenAiRequestMetadataFieldsMapToMetadata() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.openai.request.seed")
                        .setValue(AnyValue.newBuilder().setIntValue(100).build())
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.openai.request.service_tier")
                        .setValue(AnyValue.newBuilder().setStringValue("default").build())
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.openai.response.service_tier")
                        .setValue(AnyValue.newBuilder().setStringValue("scale").build())
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.openai.response.system_fingerprint")
                        .setValue(AnyValue.newBuilder().setStringValue("fp_44709d6fcb").build())
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.metadata().has("gen_ai.openai.request.seed")).isTrue();
        assertThat(span.metadata().get("gen_ai.openai.request.seed").asInt()).isEqualTo(100);
        assertThat(span.metadata().get("gen_ai.openai.request.service_tier").asText()).isEqualTo("default");
        assertThat(span.metadata().get("gen_ai.openai.response.service_tier").asText()).isEqualTo("scale");
        assertThat(span.metadata().get("gen_ai.openai.response.system_fingerprint").asText()).isEqualTo("fp_44709d6fcb");
        assertThat(span.input()).isNull();
    }

    @Test
    void testGenAiEmbeddingsDimensionCountMapsToMetadata() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.embeddings.dimension.count")
                        .setValue(AnyValue.newBuilder().setIntValue(1536).build())
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.metadata().has("gen_ai.embeddings.dimension.count")).isTrue();
        assertThat(span.metadata().get("gen_ai.embeddings.dimension.count").asInt()).isEqualTo(1536);
        assertThat(span.input()).isNull();
    }

    @Test
    void testGenAiClientMetricLikeAttributesMapToMetadata() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.client.operation.duration")
                        .setValue(AnyValue.newBuilder().setDoubleValue(123.5).build())
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.client.token.usage")
                        .setValue(AnyValue.newBuilder().setIntValue(17).build())
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.metadata().get("gen_ai.client.operation.duration").asDouble()).isEqualTo(123.5);
        assertThat(span.metadata().get("gen_ai.client.token.usage").asInt()).isEqualTo(17);
        assertThat(span.input()).isNull();
        assertThat(span.output()).isNull();
        assertThat(span.usage()).isNull();
    }

    @Test
    void testGeneralErrorAndServerAttributesMapToMetadata() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("error.type")
                        .setValue(AnyValue.newBuilder().setStringValue("timeout"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("server.address")
                        .setValue(AnyValue.newBuilder().setStringValue("api.openai.com"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("server.port")
                        .setValue(AnyValue.newBuilder().setIntValue(443))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.metadata().has("error.type")).isTrue();
        assertThat(span.metadata().get("error.type").asText()).isEqualTo("timeout");
        assertThat(span.metadata().has("server.address")).isTrue();
        assertThat(span.metadata().get("server.address").asText()).isEqualTo("api.openai.com");
        assertThat(span.metadata().has("server.port")).isTrue();
        assertThat(span.metadata().get("server.port").asInt()).isEqualTo(443);
        assertThat(span.metadata().has("error.message")).isFalse();
    }

    @Test
    void testGeneralErrorMessageAndExceptionAttributesMapToMetadata() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("error.message")
                        .setValue(AnyValue.newBuilder().setStringValue("model quota exceeded"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("exception.type")
                        .setValue(AnyValue.newBuilder().setStringValue("RateLimitError"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("exception.message")
                        .setValue(AnyValue.newBuilder().setStringValue("You have exceeded your quota"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("exception.stacktrace")
                        .setValue(AnyValue.newBuilder().setStringValue("trace-stack"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("error.stack")
                        .setValue(AnyValue.newBuilder().setStringValue("stack-text"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("error.stacktrace")
                        .setValue(AnyValue.newBuilder().setStringValue("stacktrace-text"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.metadata().get("error.message").asText()).isEqualTo("model quota exceeded");
        assertThat(span.metadata().get("exception.type").asText()).isEqualTo("RateLimitError");
        assertThat(span.metadata().get("exception.message").asText()).isEqualTo("You have exceeded your quota");
        assertThat(span.metadata().get("exception.stacktrace").asText()).isEqualTo("trace-stack");
        assertThat(span.metadata().has("error.stack")).isTrue();
        assertThat(span.metadata().get("error.stack").asText()).isEqualTo("stack-text");
        assertThat(span.metadata().has("error.stacktrace")).isTrue();
        assertThat(span.metadata().get("error.stacktrace").asText()).isEqualTo("stacktrace-text");
    }

    @Test
    void testAnthropicUsageCacheTokensAggregateToInputTokens() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.usage.input_tokens")
                        .setValue(AnyValue.newBuilder().setIntValue(100))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.usage.cache_read.input_tokens")
                        .setValue(AnyValue.newBuilder().setIntValue(30))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.usage.cache_creation.input_tokens")
                        .setValue(AnyValue.newBuilder().setIntValue(25))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.usage.output_tokens")
                        .setValue(AnyValue.newBuilder().setIntValue(50))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.usage().get("prompt_tokens")).isEqualTo(155);
        assertThat(span.usage().get("completion_tokens")).isEqualTo(50);
        assertThat(span.usage().get("cache_read.input_tokens")).isEqualTo(30);
        assertThat(span.usage().get("cache_creation.input_tokens")).isEqualTo(25);
    }

    @Test
    void testGenAiEvaluationAndDataSourceAttributesMapToMetadata() {
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.evaluation.name")
                        .setValue(AnyValue.newBuilder().setStringValue("Relevance"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.evaluation.explanation")
                        .setValue(AnyValue.newBuilder()
                                .setStringValue("The response addressed the question directly."))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.data_source.id")
                        .setValue(AnyValue.newBuilder().setStringValue("dataset-v2"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.metadata().has("gen_ai.evaluation.name")).isTrue();
        assertThat(span.metadata().get("gen_ai.evaluation.name").asText()).isEqualTo("Relevance");
        assertThat(span.metadata().has("gen_ai.evaluation.explanation")).isTrue();
        assertThat(span.metadata().get("gen_ai.data_source.id").asText()).isEqualTo("dataset-v2");
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
