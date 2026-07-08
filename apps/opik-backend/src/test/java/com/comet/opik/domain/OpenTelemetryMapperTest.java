package com.comet.opik.domain;

import com.comet.opik.api.Span;
import com.comet.opik.domain.mapping.OpenTelemetryMappingRuleFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.google.protobuf.ByteString;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.Status;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OpenTelemetryMapperTest {

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

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

    @ParameterizedTest(name = "gen_ai.usage.cost as {0} -> {2}")
    @MethodSource("genAiUsageCostCases")
    void genAiUsageCostExtractedIntoTotalEstimatedCost(String label, AnyValue costValue, BigDecimal expected) {
        // Pre-computed cost from gen_ai.usage.cost lands on the span's totalEstimatedCost for every
        // supported value shape. Malformed values (non-finite doubles, unparseable strings) are
        // skipped so they never abort span enrichment. Sibling token attributes still populate the
        // integer-only usage map without a 'cost' key — guarding against the original bug where the
        // double cost was routed there and silently dropped.
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.usage.cost")
                        .setValue(costValue)
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.usage.prompt_tokens")
                        .setValue(AnyValue.newBuilder().setIntValue(100))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.usage.completion_tokens")
                        .setValue(AnyValue.newBuilder().setIntValue(50))
                        .build());

        var spanBuilder = newSpanBuilder();

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        if (expected == null) {
            assertThat(span.totalEstimatedCost()).isNull();
        } else {
            assertThat(span.totalEstimatedCost()).isEqualByComparingTo(expected);
        }
        assertThat(span.usage()).doesNotContainKey("cost");
        assertThat(span.usage().get("prompt_tokens")).isEqualTo(100);
        assertThat(span.usage().get("completion_tokens")).isEqualTo(50);
    }

    static Stream<Arguments> genAiUsageCostCases() {
        return Stream.of(
                Arguments.of("double", AnyValue.newBuilder().setDoubleValue(0.001234).build(),
                        new BigDecimal("0.001234")),
                Arguments.of("int", AnyValue.newBuilder().setIntValue(1).build(),
                        new BigDecimal("1")),
                Arguments.of("numeric string", AnyValue.newBuilder().setStringValue("0.005").build(),
                        new BigDecimal("0.005")),
                Arguments.of("numeric string with whitespace",
                        AnyValue.newBuilder().setStringValue("  0.005  ").build(),
                        new BigDecimal("0.005")),
                Arguments.of("non-numeric string",
                        AnyValue.newBuilder().setStringValue("not a number").build(), null),
                Arguments.of("NaN", AnyValue.newBuilder().setDoubleValue(Double.NaN).build(), null),
                Arguments.of("positive infinity",
                        AnyValue.newBuilder().setDoubleValue(Double.POSITIVE_INFINITY).build(), null),
                Arguments.of("negative infinity",
                        AnyValue.newBuilder().setDoubleValue(Double.NEGATIVE_INFINITY).build(), null));
    }

    private Span.SpanBuilder newSpanBuilder() {
        // PODAM fills in id / traceId / projectId / startTime and every other field with random
        // values; null out totalEstimatedCost because enrich only sets it on successful extraction,
        // so the malformed/non-finite cost cases would otherwise inherit PODAM's random BigDecimal.
        return podamFactory.manufacturePojo(Span.class)
                .toBuilder()
                .totalEstimatedCost(null);
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
    void testLogfireLevelNumIsDroppedNotLeakedIntoInput() {
        // logfire's internal severity attribute appears on warning/error spans; it must not
        // pollute the span input.
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("logfire.level_num")
                        .setValue(AnyValue.newBuilder().setIntValue(17))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("tool_arguments")
                        .setValue(AnyValue.newBuilder().setStringValue("{\"query\":\"x\"}"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.input().has("logfire.level_num")).isFalse();
        assertThat(span.metadata() == null || !span.metadata().has("logfire.level_num")).isTrue();
        // a real input attribute alongside it is still captured
        assertThat(span.input().has("tool_arguments")).isTrue();
    }

    @Test
    void testLogfireModelNameMapsToMetadataNotModel() {
        // `model_name` rides on the PydanticAI agent-run span, which is not an LLM call. It must
        // not set the span model or flip the span type to llm — it belongs in metadata.
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("model_name")
                        .setValue(AnyValue.newBuilder().setStringValue("gemini-2.5-flash-lite"))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.model()).isNull();
        assertThat(span.type()).isNotEqualTo(SpanType.llm);
        assertThat(span.metadata().get("model_name").asText()).isEqualTo("gemini-2.5-flash-lite");
    }

    @Test
    void testInvokeAgentSpanIsTypedGeneralNotLlm() {
        // The agent-run span carries gen_ai.system_instructions (llm-typed rule) but is not an LLM
        // call; gen_ai.operation.name=invoke_agent must force it back to general.
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.operation.name")
                        .setValue(AnyValue.newBuilder().setStringValue("invoke_agent"))
                        .build(),
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

        assertThat(span.type()).isEqualTo(SpanType.general);
    }

    @Test
    void testGenAiToolCallArgumentsAndResultMapToInputAndOutput() {
        // The OTel GenAI tool-call convention carries the tool span's I/O: arguments -> input,
        // result -> output. These must win over the broad `gen_ai.tool.` METADATA prefix, while
        // other gen_ai.tool.* attributes (e.g. call.id) still go to metadata.
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.tool.call.arguments")
                        .setValue(AnyValue.newBuilder().setStringValue("{\"city\":\"Paris\"}"))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.tool.call.result")
                        .setValue(AnyValue.newBuilder().setStringValue("Sunny in Paris"))
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

        assertThat(span.input().has("gen_ai.tool.call.arguments")).isTrue();
        assertThat(span.output().has("gen_ai.tool.call.result")).isTrue();
        // a non-I/O tool attribute still lands in metadata, not input/output
        assertThat(span.metadata().has("gen_ai.tool.call.id")).isTrue();
        assertThat(span.input().has("gen_ai.tool.call.id")).isFalse();
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

    @ParameterizedTest(name = "Test total_tokens computation when missing 'gen_ai.usage.total_tokens' but only have {0} and {1}")
    @CsvSource({
            "gen_ai.usage.prompt_tokens,gen_ai.usage.completion_tokens",
            "gen_ai.usage.input_tokens,gen_ai.usage.output_tokens"
    })
    void testTotalTokensComputedWhenMissing(String promptTokensKey, String completionTokensKey) {
        // PydanticAI sends prompt_tokens and completion_tokens directly but omits total_tokens
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey(promptTokensKey)
                        .setValue(AnyValue.newBuilder().setIntValue(120))
                        .build(),
                KeyValue.newBuilder()
                        .setKey(completionTokensKey)
                        .setValue(AnyValue.newBuilder().setIntValue(80))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.usage()).isNotNull();
        assertThat(span.usage().get("prompt_tokens")).isEqualTo(120);
        assertThat(span.usage().get("completion_tokens")).isEqualTo(80);
        assertThat(span.usage().get("total_tokens")).isEqualTo(200);
    }

    @ParameterizedTest(name = "Test total_tokens computation the 'gen_ai.usage.total_tokens' is not overwritten when also have {0} and {1}")
    @CsvSource({
            "gen_ai.usage.prompt_tokens,gen_ai.usage.completion_tokens",
            "gen_ai.usage.input_tokens,gen_ai.usage.output_tokens"
    })
    void testTotalTokensNotOverwrittenWhenAlreadyPresent(String promptTokensKey, String completionTokensKey) {
        // When total_tokens is explicitly provided, it must not be overwritten
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey(promptTokensKey)
                        .setValue(AnyValue.newBuilder().setIntValue(120))
                        .build(),
                KeyValue.newBuilder()
                        .setKey(completionTokensKey)
                        .setValue(AnyValue.newBuilder().setIntValue(80))
                        .build(),
                KeyValue.newBuilder()
                        .setKey("gen_ai.usage.total_tokens")
                        .setValue(AnyValue.newBuilder().setIntValue(210)) // explicit value differs from sum
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.usage()).isNotNull();
        assertThat(span.usage().get("total_tokens")).isEqualTo(210); // original value preserved
    }

    @Test
    void testTotalTokensNotAddedWhenOnlyOneComponentPresent() {
        // total_tokens should not be fabricated when only one of the two components is available
        var attributes = List.of(
                KeyValue.newBuilder()
                        .setKey("gen_ai.usage.prompt_tokens")
                        .setValue(AnyValue.newBuilder().setIntValue(120))
                        .build());

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

        var span = spanBuilder.build();

        assertThat(span.usage()).isNotNull();
        assertThat(span.usage().containsKey("total_tokens")).isFalse();
    }

    @Nested
    class OpikTraceIdAndParentSpanIdOverride {

        private final TimeBasedEpochGenerator uuidV7Generator = Generators.timeBasedEpochGenerator();

        private io.opentelemetry.proto.trace.v1.Span buildOtelSpan(byte[] traceId, byte[] spanId,
                byte[] parentSpanId, List<KeyValue> attributes) {
            var builder = io.opentelemetry.proto.trace.v1.Span.newBuilder()
                    .setName("test span")
                    .setTraceId(ByteString.copyFrom(traceId))
                    .setSpanId(ByteString.copyFrom(spanId))
                    .setStartTimeUnixNano((System.currentTimeMillis() - 1_000) * 1_000_000L)
                    .setEndTimeUnixNano(System.currentTimeMillis() * 1_000_000L);
            if (parentSpanId != null) {
                builder.setParentSpanId(ByteString.copyFrom(parentSpanId));
            }
            attributes.forEach(builder::addAttributes);
            return builder.build();
        }

        @Test
        void toOpikSpan_withOpikTraceIdOnly_usesOverrideTraceIdAndNullParent() {
            var otelTraceId = UUID.randomUUID().toString().getBytes();
            var otelSpanId = UUID.randomUUID().toString().getBytes();
            var otelParentSpanId = UUID.randomUUID().toString().getBytes();

            var overrideTraceId = uuidV7Generator.generate();

            var otelSpan = buildOtelSpan(otelTraceId, otelSpanId, otelParentSpanId, List.of(
                    KeyValue.newBuilder()
                            .setKey("opik.trace_id")
                            .setValue(AnyValue.newBuilder().setStringValue(overrideTraceId.toString()))
                            .build()));

            var mappedTraceId = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceId,
                    System.currentTimeMillis());

            var opikSpan = OpenTelemetryMapper.toOpikSpan(otelSpan, mappedTraceId, null);

            // trace ID should be the override, not the mapped one
            assertThat(opikSpan.traceId()).isEqualTo(overrideTraceId);
            // parent span ID should be null (no opik.parent_span_id provided)
            assertThat(opikSpan.parentSpanId()).isNull();
        }

        @Test
        void toOpikSpan_withBothOpikTraceIdAndParentSpanId_usesBothOverrides() {
            var otelTraceId = UUID.randomUUID().toString().getBytes();
            var otelSpanId = UUID.randomUUID().toString().getBytes();

            var overrideTraceId = uuidV7Generator.generate();
            var overrideParentSpanId = uuidV7Generator.generate();

            var otelSpan = buildOtelSpan(otelTraceId, otelSpanId, null, List.of(
                    KeyValue.newBuilder()
                            .setKey("opik.trace_id")
                            .setValue(AnyValue.newBuilder().setStringValue(overrideTraceId.toString()))
                            .build(),
                    KeyValue.newBuilder()
                            .setKey("opik.parent_span_id")
                            .setValue(AnyValue.newBuilder().setStringValue(overrideParentSpanId.toString()))
                            .build()));

            var mappedTraceId = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceId,
                    System.currentTimeMillis());

            var opikSpan = OpenTelemetryMapper.toOpikSpan(otelSpan, mappedTraceId, null);

            assertThat(opikSpan.traceId()).isEqualTo(overrideTraceId);
            assertThat(opikSpan.parentSpanId()).isEqualTo(overrideParentSpanId);
        }

        @Test
        void toOpikSpan_withoutOverrides_usesNormalMappingBehavior() {
            var otelTraceId = UUID.randomUUID().toString().getBytes();
            var otelSpanId = UUID.randomUUID().toString().getBytes();
            var otelParentSpanId = UUID.randomUUID().toString().getBytes();

            var otelSpan = buildOtelSpan(otelTraceId, otelSpanId, otelParentSpanId, List.of());

            var mappedTraceId = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceId,
                    System.currentTimeMillis());

            var opikSpan = OpenTelemetryMapper.toOpikSpan(otelSpan, mappedTraceId, null);

            // should use the mapped trace ID (not overridden)
            assertThat(opikSpan.traceId()).isEqualTo(mappedTraceId);
            // should have a converted parent span ID (not null)
            assertThat(opikSpan.parentSpanId()).isNotNull();
        }

        @Test
        void toOpikSpan_withoutOverrides_rootSpanHasNullParent() {
            var otelTraceId = UUID.randomUUID().toString().getBytes();
            var otelSpanId = UUID.randomUUID().toString().getBytes();

            var otelSpan = buildOtelSpan(otelTraceId, otelSpanId, null, List.of());

            var mappedTraceId = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceId,
                    System.currentTimeMillis());

            var opikSpan = OpenTelemetryMapper.toOpikSpan(otelSpan, mappedTraceId, null);

            assertThat(opikSpan.traceId()).isEqualTo(mappedTraceId);
            assertThat(opikSpan.parentSpanId()).isNull();
        }

        @Test
        void toOpikSpan_opikTraceIdAndParentSpanId_areDroppedFromAttributes() {
            var otelTraceId = UUID.randomUUID().toString().getBytes();
            var otelSpanId = UUID.randomUUID().toString().getBytes();

            var overrideTraceId = uuidV7Generator.generate();
            var overrideParentSpanId = uuidV7Generator.generate();

            var otelSpan = buildOtelSpan(otelTraceId, otelSpanId, null, List.of(
                    KeyValue.newBuilder()
                            .setKey("opik.trace_id")
                            .setValue(AnyValue.newBuilder().setStringValue(overrideTraceId.toString()))
                            .build(),
                    KeyValue.newBuilder()
                            .setKey("opik.parent_span_id")
                            .setValue(AnyValue.newBuilder().setStringValue(overrideParentSpanId.toString()))
                            .build(),
                    KeyValue.newBuilder()
                            .setKey("gen_ai.request.model")
                            .setValue(AnyValue.newBuilder().setStringValue("gpt-4"))
                            .build()));

            var mappedTraceId = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceId,
                    System.currentTimeMillis());

            var opikSpan = OpenTelemetryMapper.toOpikSpan(otelSpan, mappedTraceId, null);

            // opik.trace_id and opik.parent_span_id should NOT appear in input, output, or metadata
            if (opikSpan.input() != null) {
                assertThat(opikSpan.input().has("opik.trace_id")).isFalse();
                assertThat(opikSpan.input().has("opik.parent_span_id")).isFalse();
            }
            if (opikSpan.output() != null) {
                assertThat(opikSpan.output().has("opik.trace_id")).isFalse();
                assertThat(opikSpan.output().has("opik.parent_span_id")).isFalse();
            }
            if (opikSpan.metadata() != null) {
                assertThat(opikSpan.metadata().has("opik.trace_id")).isFalse();
                assertThat(opikSpan.metadata().has("opik.parent_span_id")).isFalse();
            }
            // other attributes should still be processed normally
            assertThat(opikSpan.model()).isEqualTo("gpt-4");
        }

        @Test
        void extractOpikTraceId_returnsUUID_whenAttributePresent() {
            var expectedId = uuidV7Generator.generate();
            var otelSpan = buildOtelSpan(
                    UUID.randomUUID().toString().getBytes(),
                    UUID.randomUUID().toString().getBytes(),
                    null,
                    List.of(KeyValue.newBuilder()
                            .setKey("opik.trace_id")
                            .setValue(AnyValue.newBuilder().setStringValue(expectedId.toString()))
                            .build()));

            var result = OpenTelemetryMapper.extractOpikTraceId(otelSpan);
            assertThat(result).isPresent().contains(expectedId);
        }

        @Test
        void extractOpikTraceId_returnsEmpty_whenAttributeAbsent() {
            var otelSpan = buildOtelSpan(
                    UUID.randomUUID().toString().getBytes(),
                    UUID.randomUUID().toString().getBytes(),
                    null,
                    List.of());

            var result = OpenTelemetryMapper.extractOpikTraceId(otelSpan);
            assertThat(result).isEmpty();
        }

        @Test
        void extractOpikParentSpanId_returnsUUID_whenAttributePresent() {
            var expectedId = uuidV7Generator.generate();
            var otelSpan = buildOtelSpan(
                    UUID.randomUUID().toString().getBytes(),
                    UUID.randomUUID().toString().getBytes(),
                    null,
                    List.of(KeyValue.newBuilder()
                            .setKey("opik.parent_span_id")
                            .setValue(AnyValue.newBuilder().setStringValue(expectedId.toString()))
                            .build()));

            var result = OpenTelemetryMapper.extractOpikParentSpanId(otelSpan);
            assertThat(result).isPresent().contains(expectedId);
        }

        @Test
        void extractOpikParentSpanId_returnsEmpty_whenAttributeAbsent() {
            var otelSpan = buildOtelSpan(
                    UUID.randomUUID().toString().getBytes(),
                    UUID.randomUUID().toString().getBytes(),
                    null,
                    List.of());

            var result = OpenTelemetryMapper.extractOpikParentSpanId(otelSpan);
            assertThat(result).isEmpty();
        }

        @Test
        void extractOpikTraceId_returnsEmpty_whenNotUUIDv7() {
            // UUID v4 should be rejected
            var v4Uuid = UUID.randomUUID();
            var otelSpan = buildOtelSpan(
                    UUID.randomUUID().toString().getBytes(),
                    UUID.randomUUID().toString().getBytes(),
                    null,
                    List.of(KeyValue.newBuilder()
                            .setKey("opik.trace_id")
                            .setValue(AnyValue.newBuilder().setStringValue(v4Uuid.toString()))
                            .build()));

            var result = OpenTelemetryMapper.extractOpikTraceId(otelSpan);
            assertThat(result).isEmpty();
        }

        @Test
        void extractOpikTraceId_returnsEmpty_whenInvalidUUIDString() {
            var otelSpan = buildOtelSpan(
                    UUID.randomUUID().toString().getBytes(),
                    UUID.randomUUID().toString().getBytes(),
                    null,
                    List.of(KeyValue.newBuilder()
                            .setKey("opik.trace_id")
                            .setValue(AnyValue.newBuilder().setStringValue("not-a-uuid"))
                            .build()));

            var result = OpenTelemetryMapper.extractOpikTraceId(otelSpan);
            assertThat(result).isEmpty();
        }

        @Test
        void extractOpikTraceId_returnsEmpty_whenAttributeBlank() {
            var otelSpan = buildOtelSpan(
                    UUID.randomUUID().toString().getBytes(),
                    UUID.randomUUID().toString().getBytes(),
                    null,
                    List.of(KeyValue.newBuilder()
                            .setKey("opik.trace_id")
                            .setValue(AnyValue.newBuilder().setStringValue(""))
                            .build()));

            var result = OpenTelemetryMapper.extractOpikTraceId(otelSpan);
            assertThat(result).isEmpty();
        }

        @Test
        void toOpikSpan_opikParentSpanIdWithoutTraceId_isIgnored() {
            // opik.parent_span_id without opik.trace_id should be ignored (normal flow)
            var otelTraceId = UUID.randomUUID().toString().getBytes();
            var otelSpanId = UUID.randomUUID().toString().getBytes();
            var otelParentSpanId = UUID.randomUUID().toString().getBytes();

            var overrideParentSpanId = uuidV7Generator.generate();

            var otelSpan = buildOtelSpan(otelTraceId, otelSpanId, otelParentSpanId, List.of(
                    KeyValue.newBuilder()
                            .setKey("opik.parent_span_id")
                            .setValue(AnyValue.newBuilder().setStringValue(overrideParentSpanId.toString()))
                            .build()));

            var mappedTraceId = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceId,
                    System.currentTimeMillis());

            var opikSpan = OpenTelemetryMapper.toOpikSpan(otelSpan, mappedTraceId, null);

            // trace ID should use normal mapping (no override)
            assertThat(opikSpan.traceId()).isEqualTo(mappedTraceId);
            // parent span ID should use normal OTEL conversion (override ignored)
            assertThat(opikSpan.parentSpanId()).isNotNull();
            assertThat(opikSpan.parentSpanId()).isNotEqualTo(overrideParentSpanId);
        }
    }

    @Nested
    class OpikSpanIdOverride {

        private final TimeBasedEpochGenerator uuidV7Generator = Generators.timeBasedEpochGenerator();

        private io.opentelemetry.proto.trace.v1.Span buildOtelSpan(byte[] traceId, byte[] spanId,
                byte[] parentSpanId, List<KeyValue> attributes) {
            var builder = io.opentelemetry.proto.trace.v1.Span.newBuilder()
                    .setName("test span")
                    .setTraceId(ByteString.copyFrom(traceId))
                    .setSpanId(ByteString.copyFrom(spanId))
                    .setStartTimeUnixNano((System.currentTimeMillis() - 1_000) * 1_000_000L)
                    .setEndTimeUnixNano(System.currentTimeMillis() * 1_000_000L);
            if (parentSpanId != null) {
                builder.setParentSpanId(ByteString.copyFrom(parentSpanId));
            }
            attributes.forEach(builder::addAttributes);
            return builder.build();
        }

        @Test
        void extractOpikSpanId_returnsUUID_whenAttributePresent() {
            var expectedId = uuidV7Generator.generate();
            var otelSpan = buildOtelSpan(
                    UUID.randomUUID().toString().getBytes(),
                    UUID.randomUUID().toString().getBytes(),
                    null,
                    List.of(KeyValue.newBuilder()
                            .setKey("opik.span_id")
                            .setValue(AnyValue.newBuilder().setStringValue(expectedId.toString()))
                            .build()));

            var result = OpenTelemetryMapper.extractOpikSpanId(otelSpan);
            assertThat(result).isPresent().contains(expectedId);
        }

        @Test
        void extractOpikSpanId_returnsEmpty_whenAttributeAbsent() {
            var otelSpan = buildOtelSpan(
                    UUID.randomUUID().toString().getBytes(),
                    UUID.randomUUID().toString().getBytes(),
                    null,
                    List.of());

            assertThat(OpenTelemetryMapper.extractOpikSpanId(otelSpan)).isEmpty();
        }

        @Test
        void extractOpikSpanId_returnsEmpty_whenNotUUIDv7() {
            var v4 = UUID.randomUUID();
            var otelSpan = buildOtelSpan(
                    UUID.randomUUID().toString().getBytes(),
                    UUID.randomUUID().toString().getBytes(),
                    null,
                    List.of(KeyValue.newBuilder()
                            .setKey("opik.span_id")
                            .setValue(AnyValue.newBuilder().setStringValue(v4.toString()))
                            .build()));

            assertThat(OpenTelemetryMapper.extractOpikSpanId(otelSpan)).isEmpty();
        }

        @Test
        void toOpikSpan_withOpikSpanIdOnly_usesOverrideForIdAndConvertsParentNormally() {
            // opik.span_id alone (no trace_id) is honored for the span id; trace/parent flow is unchanged.
            var otelTraceId = UUID.randomUUID().toString().getBytes();
            var otelSpanId = UUID.randomUUID().toString().getBytes();
            var otelParentSpanId = UUID.randomUUID().toString().getBytes();

            var overrideSpanId = uuidV7Generator.generate();

            var otelSpan = buildOtelSpan(otelTraceId, otelSpanId, otelParentSpanId, List.of(
                    KeyValue.newBuilder()
                            .setKey("opik.span_id")
                            .setValue(AnyValue.newBuilder().setStringValue(overrideSpanId.toString()))
                            .build()));

            var mappedTraceId = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceId,
                    System.currentTimeMillis());

            var opikSpan = OpenTelemetryMapper.toOpikSpan(otelSpan, mappedTraceId, null);

            assertThat(opikSpan.id()).isEqualTo(overrideSpanId);
            assertThat(opikSpan.traceId()).isEqualTo(mappedTraceId);
            assertThat(opikSpan.parentSpanId()).isNotNull();
        }

        @Test
        void toOpikSpan_withFullTriple_usesAllOverridesVerbatim() {
            // SDK OpikSpanProcessor case: trace_id, span_id, parent_span_id all set.
            var otelTraceId = UUID.randomUUID().toString().getBytes();
            var otelSpanId = UUID.randomUUID().toString().getBytes();
            var otelParentSpanId = UUID.randomUUID().toString().getBytes();

            var overrideTraceId = uuidV7Generator.generate();
            var overrideSpanId = uuidV7Generator.generate();
            var overrideParentSpanId = uuidV7Generator.generate();

            var otelSpan = buildOtelSpan(otelTraceId, otelSpanId, otelParentSpanId, List.of(
                    KeyValue.newBuilder()
                            .setKey("opik.trace_id")
                            .setValue(AnyValue.newBuilder().setStringValue(overrideTraceId.toString()))
                            .build(),
                    KeyValue.newBuilder()
                            .setKey("opik.span_id")
                            .setValue(AnyValue.newBuilder().setStringValue(overrideSpanId.toString()))
                            .build(),
                    KeyValue.newBuilder()
                            .setKey("opik.parent_span_id")
                            .setValue(AnyValue.newBuilder().setStringValue(overrideParentSpanId.toString()))
                            .build()));

            var mappedTraceId = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceId,
                    System.currentTimeMillis());

            var opikSpan = OpenTelemetryMapper.toOpikSpan(otelSpan, mappedTraceId, null);

            assertThat(opikSpan.id()).isEqualTo(overrideSpanId);
            assertThat(opikSpan.traceId()).isEqualTo(overrideTraceId);
            assertThat(opikSpan.parentSpanId()).isEqualTo(overrideParentSpanId);
        }

        @Test
        void toOpikSpan_processorChain_childParentSpanIdMatchesParentOverrideSpanId() {
            // Simulates the SDK processor chaining behavior: parent's opik.span_id is
            // threaded into each child's opik.parent_span_id. Child should land at
            // parentSpanId == parent.id even though OTEL ids and the batch's mapped trace id differ.
            var otelTraceIdA = UUID.randomUUID().toString().getBytes();
            var otelTraceIdB = UUID.randomUUID().toString().getBytes(); // simulates a different OTEL trace
            var otelParentSpanId = UUID.randomUUID().toString().getBytes();
            var otelChildSpanId = UUID.randomUUID().toString().getBytes();

            var overrideTraceId = uuidV7Generator.generate();
            var parentOpikSpanId = uuidV7Generator.generate();
            var childOpikSpanId = uuidV7Generator.generate();
            var attachedToOpikParentId = uuidV7Generator.generate();

            // Parent OTEL span carries trace_id + span_id + (attached) parent_span_id
            var parentSpan = buildOtelSpan(otelTraceIdA, otelParentSpanId, null, List.of(
                    KeyValue.newBuilder().setKey("opik.trace_id")
                            .setValue(AnyValue.newBuilder().setStringValue(overrideTraceId.toString())).build(),
                    KeyValue.newBuilder().setKey("opik.span_id")
                            .setValue(AnyValue.newBuilder().setStringValue(parentOpikSpanId.toString())).build(),
                    KeyValue.newBuilder().setKey("opik.parent_span_id")
                            .setValue(AnyValue.newBuilder().setStringValue(attachedToOpikParentId.toString()))
                            .build()));

            // Child OTEL span carries trace_id + span_id + parent_span_id pointing at parent's opik.span_id
            var childSpan = buildOtelSpan(otelTraceIdB, otelChildSpanId, otelParentSpanId, List.of(
                    KeyValue.newBuilder().setKey("opik.trace_id")
                            .setValue(AnyValue.newBuilder().setStringValue(overrideTraceId.toString())).build(),
                    KeyValue.newBuilder().setKey("opik.span_id")
                            .setValue(AnyValue.newBuilder().setStringValue(childOpikSpanId.toString())).build(),
                    KeyValue.newBuilder().setKey("opik.parent_span_id")
                            .setValue(AnyValue.newBuilder().setStringValue(parentOpikSpanId.toString())).build()));

            var mappedTraceIdA = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceIdA,
                    System.currentTimeMillis());
            var mappedTraceIdB = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceIdB,
                    System.currentTimeMillis());

            var opikParent = OpenTelemetryMapper.toOpikSpan(parentSpan, mappedTraceIdA, null);
            var opikChild = OpenTelemetryMapper.toOpikSpan(childSpan, mappedTraceIdB, null);

            // Both spans land in the override trace, regardless of which OTEL trace they came from.
            assertThat(opikParent.traceId()).isEqualTo(overrideTraceId);
            assertThat(opikChild.traceId()).isEqualTo(overrideTraceId);

            // Parent-child link is preserved verbatim through the chain.
            assertThat(opikParent.id()).isEqualTo(parentOpikSpanId);
            assertThat(opikChild.id()).isEqualTo(childOpikSpanId);
            assertThat(opikChild.parentSpanId()).isEqualTo(parentOpikSpanId);
        }

        @Test
        void toOpikSpan_opikSpanId_isDroppedFromAttributes() {
            var otelTraceId = UUID.randomUUID().toString().getBytes();
            var otelSpanId = UUID.randomUUID().toString().getBytes();
            var overrideSpanId = uuidV7Generator.generate();

            var otelSpan = buildOtelSpan(otelTraceId, otelSpanId, null, List.of(
                    KeyValue.newBuilder().setKey("opik.span_id")
                            .setValue(AnyValue.newBuilder().setStringValue(overrideSpanId.toString())).build()));

            var mappedTraceId = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceId,
                    System.currentTimeMillis());

            var opikSpan = OpenTelemetryMapper.toOpikSpan(otelSpan, mappedTraceId, null);

            if (opikSpan.input() != null) {
                assertThat(opikSpan.input().has("opik.span_id")).isFalse();
            }
            if (opikSpan.metadata() != null) {
                assertThat(opikSpan.metadata().has("opik.span_id")).isFalse();
            }
        }
    }

    /**
     * Verifies the Elastic Inference Service integration: when an OTel span emits
     * {@code gen_ai.system = "elastic"} and a routed model ID, the mapper rewrites
     * model/provider to the underlying provider so cost lookup and provider-based
     * filtering downstream see the real upstream. Original values are kept in metadata.
     */
    @Nested
    class ElasticInferenceService {

        private Span mapEis(String modelId) {
            var attributes = List.of(
                    KeyValue.newBuilder()
                            .setKey("gen_ai.system")
                            .setValue(AnyValue.newBuilder().setStringValue("elastic"))
                            .build(),
                    KeyValue.newBuilder()
                            .setKey("gen_ai.request.model")
                            .setValue(AnyValue.newBuilder().setStringValue(modelId))
                            .build(),
                    KeyValue.newBuilder()
                            .setKey("gen_ai.usage.input_tokens")
                            .setValue(AnyValue.newBuilder().setIntValue(100))
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

            return spanBuilder.build();
        }

        // Jina row keeps the "jina-" prefix in the model key, so the resolver must not strip it.
        @ParameterizedTest(name = "EIS prefix ''{0}'' -> provider=''{1}'', model=''{2}''")
        @CsvSource({
                "anthropic-claude-4.5-sonnet, anthropic,  claude-4.5-sonnet",
                "openai-gpt-oss-120b,         openai,     gpt-oss-120b",
                "jina-embeddings-v3,          jina_ai,    jina-embeddings-v3",
                "google-gemini-3-flash,       google_ai,  gemini-3-flash",
                "microsoft-multilingual-e5-large, azure,  multilingual-e5-large",
        })
        void prefixStripsAndRewritesProvider(String modelId, String expectedProvider, String expectedModel) {
            var span = mapEis(modelId);

            assertThat(span.provider()).isEqualTo(expectedProvider);
            assertThat(span.model()).isEqualTo(expectedModel);
        }

        @Test
        void elasticOwnModelWithoutDashIsLeftUnchanged() {
            var span = mapEis("elser_model_2");

            // No '-' in the model ID → resolver no-op; span retains the original gen_ai.system attribution.
            // Metadata may be null when nothing else added attributes — that's also fine.
            assertThat(span.provider()).isEqualTo("elastic");
            assertThat(span.model()).isEqualTo("elser_model_2");
            assertThat(span.metadata() == null || !span.metadata().has("eis_original_system")).isTrue();
            assertThat(span.metadata() == null || !span.metadata().has("eis_original_model")).isTrue();
        }

        @Test
        void rewriteRecordsOriginalsInMetadata() {
            var span = mapEis("anthropic-claude-4.5-sonnet");

            assertThat(span.metadata().get("eis_original_system").asText()).isEqualTo("elastic");
            assertThat(span.metadata().get("eis_original_model").asText()).isEqualTo("anthropic-claude-4.5-sonnet");
        }

        @Test
        void nonElasticProviderIsNotRewritten() {
            // gen_ai.system other than "elastic" must leave the resolver inert, even for hyphenated IDs
            var attributes = List.of(
                    KeyValue.newBuilder()
                            .setKey("gen_ai.system")
                            .setValue(AnyValue.newBuilder().setStringValue("openai"))
                            .build(),
                    KeyValue.newBuilder()
                            .setKey("gen_ai.request.model")
                            .setValue(AnyValue.newBuilder().setStringValue("anthropic-claude-4.5-sonnet"))
                            .build());

            var spanBuilder = Span.builder()
                    .id(UUID.randomUUID())
                    .traceId(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .startTime(Instant.now());

            OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);
            var span = spanBuilder.build();

            assertThat(span.provider()).isEqualTo("openai");
            assertThat(span.model()).isEqualTo("anthropic-claude-4.5-sonnet");
            assertThat(span.metadata() == null || !span.metadata().has("eis_original_system")).isTrue();
        }

        @Test
        void elasticSystemWithoutModelIsNoop() {
            var attributes = List.of(
                    KeyValue.newBuilder()
                            .setKey("gen_ai.system")
                            .setValue(AnyValue.newBuilder().setStringValue("elastic"))
                            .build());

            var spanBuilder = Span.builder()
                    .id(UUID.randomUUID())
                    .traceId(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .startTime(Instant.now());

            OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);
            var span = spanBuilder.build();

            assertThat(span.provider()).isEqualTo("elastic");
            assertThat(span.model()).isNull();
        }
    }

    /**
     * PydanticAI tool-execution spans carry the result under {@code tool_response} and the call
     * arguments under {@code tool_arguments}. Verifies the result lands in output (not the default
     * input bucket) so the tool span renders its output in the UI.
     */
    @Nested
    class PydanticToolSpan {

        @Test
        void toolResponseMapsToOutputAndArgumentsToInput() {
            var attributes = List.of(
                    KeyValue.newBuilder()
                            .setKey("tool_arguments")
                            .setValue(AnyValue.newBuilder().setStringValue("{\"query\":\"Opik\"}"))
                            .build(),
                    KeyValue.newBuilder()
                            .setKey("tool_response")
                            .setValue(AnyValue.newBuilder().setStringValue("Top result for 'Opik'"))
                            .build());

            var spanBuilder = Span.builder()
                    .id(UUID.randomUUID())
                    .traceId(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .startTime(Instant.now());

            OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

            var span = spanBuilder.build();

            assertThat(span.output().has("tool_response")).isTrue();
            assertThat(span.input().has("tool_arguments")).isTrue();
            assertThat(span.input().has("tool_response")).isFalse();
        }
    }

    /**
     * The generic {@code "google"} provider (PydanticAI / google-genai) must be resolved to the
     * Vertex AI vs Gemini API canonical name via {@code server.address}, otherwise cost lookup
     * can't match a price row.
     */
    @Nested
    class GoogleProviderResolution {

        private Span mapGoogle(String serverAddress) {
            var attributes = new java.util.ArrayList<KeyValue>();
            attributes.add(KeyValue.newBuilder()
                    .setKey("gen_ai.system")
                    .setValue(AnyValue.newBuilder().setStringValue("google"))
                    .build());
            if (serverAddress != null) {
                attributes.add(KeyValue.newBuilder()
                        .setKey("server.address")
                        .setValue(AnyValue.newBuilder().setStringValue(serverAddress))
                        .build());
            }

            var spanBuilder = Span.builder()
                    .id(UUID.randomUUID())
                    .traceId(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .startTime(Instant.now());

            OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

            return spanBuilder.build();
        }

        @Test
        void vertexHostResolvesToGoogleVertexAi() {
            assertThat(mapGoogle("us-east1-aiplatform.googleapis.com").provider()).isEqualTo("google_vertexai");
        }

        @Test
        void geminiApiHostResolvesToGoogleAi() {
            assertThat(mapGoogle("generativelanguage.googleapis.com").provider()).isEqualTo("google_ai");
        }

        @Test
        void missingServerAddressDefaultsToGoogleAi() {
            assertThat(mapGoogle(null).provider()).isEqualTo("google_ai");
        }

        @Test
        void nonGoogleProviderIsNotRewritten() {
            var attributes = List.of(
                    KeyValue.newBuilder()
                            .setKey("gen_ai.system")
                            .setValue(AnyValue.newBuilder().setStringValue("openai"))
                            .build(),
                    KeyValue.newBuilder()
                            .setKey("server.address")
                            .setValue(AnyValue.newBuilder().setStringValue("us-east1-aiplatform.googleapis.com"))
                            .build());

            var spanBuilder = Span.builder()
                    .id(UUID.randomUUID())
                    .traceId(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .startTime(Instant.now());

            OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

            assertThat(spanBuilder.build().provider()).isEqualTo("openai");
        }
    }

    /**
     * Failed spans must surface as Opik errors. Both OTel-core signals are covered: the
     * {@code exception} span event and a {@code STATUS_CODE_ERROR} status.
     */
    @Nested
    class ErrorInfoExtraction {

        private final TimeBasedEpochGenerator uuidV7Generator = Generators.timeBasedEpochGenerator();

        private io.opentelemetry.proto.trace.v1.Span.Builder baseSpan() {
            return io.opentelemetry.proto.trace.v1.Span.newBuilder()
                    .setName("running tool")
                    .setTraceId(ByteString.copyFrom(UUID.randomUUID().toString().getBytes()))
                    .setSpanId(ByteString.copyFrom(UUID.randomUUID().toString().getBytes()))
                    .setStartTimeUnixNano((System.currentTimeMillis() - 1_000) * 1_000_000L)
                    .setEndTimeUnixNano(System.currentTimeMillis() * 1_000_000L);
        }

        private Span map(io.opentelemetry.proto.trace.v1.Span otelSpan) {
            return OpenTelemetryMapper.toOpikSpan(otelSpan, uuidV7Generator.generate(), null);
        }

        @Test
        void exceptionEventPopulatesErrorInfo() {
            var otelSpan = baseSpan()
                    .addEvents(io.opentelemetry.proto.trace.v1.Span.Event.newBuilder()
                            .setName("exception")
                            .addAttributes(KeyValue.newBuilder().setKey("exception.type")
                                    .setValue(AnyValue.newBuilder().setStringValue("RuntimeError")))
                            .addAttributes(KeyValue.newBuilder().setKey("exception.message")
                                    .setValue(AnyValue.newBuilder().setStringValue("firecrawl: 502 Bad Gateway")))
                            .addAttributes(KeyValue.newBuilder().setKey("exception.stacktrace")
                                    .setValue(
                                            AnyValue.newBuilder().setStringValue("Traceback (most recent call last)")))
                            .build())
                    .build();

            var errorInfo = map(otelSpan).errorInfo();

            assertThat(errorInfo).isNotNull();
            assertThat(errorInfo.exceptionType()).isEqualTo("RuntimeError");
            assertThat(errorInfo.message()).isEqualTo("firecrawl: 502 Bad Gateway");
            assertThat(errorInfo.traceback()).isEqualTo("Traceback (most recent call last)");
        }

        @Test
        void errorStatusWithoutExceptionEventPopulatesErrorInfo() {
            var otelSpan = baseSpan()
                    .setStatus(Status.newBuilder()
                            .setCode(Status.StatusCode.STATUS_CODE_ERROR)
                            .setMessage("tool failed")
                            .build())
                    .build();

            var errorInfo = map(otelSpan).errorInfo();

            assertThat(errorInfo).isNotNull();
            assertThat(errorInfo.exceptionType()).isEqualTo("Error");
            assertThat(errorInfo.message()).isEqualTo("tool failed");
            assertThat(errorInfo.traceback()).isEqualTo("tool failed");
        }

        @Test
        void exceptionEventTakesPrecedenceOverErrorStatus() {
            var otelSpan = baseSpan()
                    .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR).build())
                    .addEvents(io.opentelemetry.proto.trace.v1.Span.Event.newBuilder()
                            .setName("exception")
                            .addAttributes(KeyValue.newBuilder().setKey("exception.type")
                                    .setValue(AnyValue.newBuilder().setStringValue("ValueError")))
                            .build())
                    .build();

            assertThat(map(otelSpan).errorInfo().exceptionType()).isEqualTo("ValueError");
        }

        @Test
        void successfulSpanHasNoErrorInfo() {
            var okSpan = baseSpan()
                    .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_OK).build())
                    .build();

            assertThat(map(okSpan).errorInfo()).isNull();
        }
    }

    @Nested
    class ClaudeCodeMappingTest {

        private static final String CLAUDE_CODE = OpenTelemetryMappingRuleFactory.CLAUDE_CODE_INSTRUMENTATION;

        private Span enrich(List<KeyValue> attributes, List<io.opentelemetry.proto.trace.v1.Span.Event> events) {
            return enrich(attributes, events, "claude_code.interaction");
        }

        private Span enrich(List<KeyValue> attributes, List<io.opentelemetry.proto.trace.v1.Span.Event> events,
                String spanName) {
            var spanBuilder = Span.builder()
                    .id(UUID.randomUUID())
                    .traceId(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .startTime(Instant.now());
            OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, CLAUDE_CODE, events, spanName);
            return spanBuilder.build();
        }

        private KeyValue intVal(String key, long value) {
            return KeyValue.newBuilder()
                    .setKey(key)
                    .setValue(AnyValue.newBuilder().setIntValue(value))
                    .build();
        }

        private KeyValue str(String key, String value) {
            return KeyValue.newBuilder()
                    .setKey(key)
                    .setValue(AnyValue.newBuilder().setStringValue(value))
                    .build();
        }

        @Test
        void userPromptMapsToInput() {
            var span = enrich(List.of(str("user_prompt", "What is the marker?")), null);

            assertThat(span.input().get("user_prompt").asText()).isEqualTo("What is the marker?");
        }

        @Test
        void toolInputMapsToInput() {
            var span = enrich(List.of(str("tool_input", "[TOOL INPUT: Bash]\n{\"command\":\"ls\"}")), null);

            assertThat(span.input().get("tool_input").asText()).isEqualTo("[TOOL INPUT: Bash]\n{\"command\":\"ls\"}");
        }

        @Test
        void responseModelOutputMapsToOutput() {
            var span = enrich(List.of(str("response.model_output", "The marker is OTEL-REPRO-MARKER-12345.")), null);

            assertThat(span.output().get("response.model_output").asText())
                    .isEqualTo("The marker is OTEL-REPRO-MARKER-12345.");
            assertThat(span.input()).isNull();
        }

        @Test
        void llmResponseFieldsMapToOutput() {
            var span = enrich(List.of(
                    str("response.model_output", "calling a tool"),
                    str("stop_reason", "tool_use"),
                    KeyValue.newBuilder().setKey("response.has_tool_call")
                            .setValue(AnyValue.newBuilder().setBoolValue(true)).build(),
                    str("request_id", "req_abc"),
                    intVal("ttft_ms", 1230)),
                    null, "claude_code.llm_request");

            assertThat(span.output().get("stop_reason").asText()).isEqualTo("tool_use");
            assertThat(span.output().get("response.has_tool_call").asBoolean()).isTrue();
            // call identifiers / latency stay in metadata, not output
            assertThat(span.output().has("request_id")).isFalse();
            assertThat(span.metadata().get("request_id").asText()).isEqualTo("req_abc");
            assertThat(span.metadata().get("ttft_ms").asInt()).isEqualTo(1230);
        }

        @Test
        void modelOutputTruncationSiblingsGoToMetadata() {
            var span = enrich(List.of(
                    str("response.model_output", "partial"),
                    KeyValue.newBuilder().setKey("response.model_output_truncated")
                            .setValue(AnyValue.newBuilder().setBoolValue(true)).build(),
                    KeyValue.newBuilder().setKey("response.model_output_original_length")
                            .setValue(AnyValue.newBuilder().setIntValue(120)).build()),
                    null);

            assertThat(span.output().has("response.model_output")).isTrue();
            assertThat(span.metadata().has("response.model_output_truncated")).isTrue();
            assertThat(span.metadata().get("response.model_output_original_length").asInt()).isEqualTo(120);
        }

        @ParameterizedTest
        @CsvSource({
                "output, AGENTS.md README.md",
                "content, marker: OTEL-REPRO-MARKER-12345"
        })
        void toolOutputEventMapsToOutput(String contentKey, String value) {
            var event = io.opentelemetry.proto.trace.v1.Span.Event.newBuilder()
                    .setName("tool.output")
                    .addAttributes(str(contentKey, value))
                    .build();

            var span = enrich(List.of(), List.of(event));

            assertThat(span.output().get(contentKey).asText()).isEqualTo(value);
        }

        @Test
        void sessionIdMapsToThreadId() {
            var span = enrich(List.of(str("session.id", "session-abc-123")), null);

            assertThat(span.metadata().get("thread_id").asText()).isEqualTo("session-abc-123");
            assertThat(span.input()).isNull();
        }

        @Test
        void tokensMapToUsage() {
            var span = enrich(List.of(
                    intVal("input_tokens", 1234),
                    intVal("output_tokens", 26),
                    intVal("cache_read_tokens", 100),
                    intVal("cache_creation_tokens", 50)),
                    null);

            assertThat(span.usage().get("prompt_tokens")).isEqualTo(1234);
            assertThat(span.usage().get("completion_tokens")).isEqualTo(26);
            // Normalized to the names the Anthropic cache-cost calculator recognizes.
            assertThat(span.usage().get("cache_read_input_tokens")).isEqualTo(100);
            assertThat(span.usage().get("cache_creation_input_tokens")).isEqualTo(50);
            assertThat(span.input()).isNull();
            assertThat(span.output()).isNull();
        }

        @Test
        void unmappedAttributesGoToMetadataNotInput() {
            var span = enrich(List.of(
                    str("user.id", "user-1"),
                    str("terminal.type", "ghostty"),
                    str("span.type", "interaction"),
                    str("user_prompt", "hello")),
                    null);

            // Only the promoted content is input; the session/identity noise is metadata.
            assertThat(span.input().get("user_prompt").asText()).isEqualTo("hello");
            assertThat(span.input().has("user.id")).isFalse();
            assertThat(span.metadata().get("user.id").asText()).isEqualTo("user-1");
            assertThat(span.metadata().get("terminal.type").asText()).isEqualTo("ghostty");
        }

        @Test
        void hookIdentityMapsToInput() {
            var span = enrich(List.of(
                    str("hook_event", "UserPromptSubmit"),
                    str("hook_name", "UserPromptSubmit"),
                    str("hook_definitions", "[{\"type\":\"command\"}]"),
                    intVal("num_hooks", 1),
                    str("user.id", "user-1")),
                    null);

            assertThat(span.input().get("hook_event").asText()).isEqualTo("UserPromptSubmit");
            assertThat(span.input().get("hook_name").asText()).isEqualTo("UserPromptSubmit");
            assertThat(span.input().has("hook_definitions")).isTrue();
            // counters and identity stay in metadata
            assertThat(span.metadata().has("num_hooks")).isTrue();
            assertThat(span.metadata().get("user.id").asText()).isEqualTo("user-1");
        }

        @Test
        void llmRequestSetupMapsToModelAndProviderNotInput() {
            var span = enrich(List.of(
                    str("model", "claude-sonnet-4-6"),
                    str("system_prompt_preview", "You are a Claude agent..."),
                    str("llm_request.context", "interaction"),
                    str("query_source", "sdk"),
                    str("user.id", "user-1")),
                    null);

            // 'model' sets the span's model field (needed for cost calc), not input
            assertThat(span.model()).isEqualTo("claude-sonnet-4-6");
            assertThat(span.input().has("model")).isFalse();
            assertThat(span.input().get("system_prompt_preview").asText()).isEqualTo("You are a Claude agent...");
            // Claude Code is Anthropic-only and never sends a provider attribute
            assertThat(span.provider()).isEqualTo("anthropic");
            assertThat(span.type()).isEqualTo(SpanType.llm);
            // request classifiers / identity stay in metadata
            assertThat(span.metadata().get("llm_request.context").asText()).isEqualTo("interaction");
            assertThat(span.metadata().get("query_source").asText()).isEqualTo("sdk");
            assertThat(span.metadata().get("user.id").asText()).isEqualTo("user-1");
        }

        @Test
        void toolInputSetsToolSpanType() {
            var span = enrich(List.of(str("tool_input", "[TOOL INPUT: Bash]\n{\"command\":\"ls\"}")), null);

            assertThat(span.type()).isEqualTo(SpanType.tool);
        }

        @Test
        void providerIsAnthropicEvenWithoutModelAttribute() {
            var span = enrich(List.of(str("user_prompt", "hello")), null);

            assertThat(span.provider()).isEqualTo("anthropic");
        }

        @Test
        void newContextMapsToInputOnLlmRequestSpan() {
            var span = enrich(List.of(str("new_context", "[TOOL RESULT: t1]\nAGENTS.md README.md")),
                    null, "claude_code.llm_request");

            assertThat(span.input().get("new_context").asText()).isEqualTo("[TOOL RESULT: t1]\nAGENTS.md README.md");
        }

        @Test
        void newContextGoesToMetadataOnNonLlmSpans() {
            var span = enrich(List.of(
                    str("user_prompt", "hello"),
                    str("new_context", "[USER PROMPT]\nhello")),
                    null, "claude_code.interaction");

            assertThat(span.input().has("new_context")).isFalse();
            assertThat(span.metadata().get("new_context").asText()).isEqualTo("[USER PROMPT]\nhello");
        }
    }
}
