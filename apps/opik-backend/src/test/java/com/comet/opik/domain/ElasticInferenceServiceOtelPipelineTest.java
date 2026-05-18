package com.comet.opik.domain;

import com.comet.opik.api.Span;
import com.comet.opik.domain.cost.CostService;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke test for the Elastic Inference Service ingestion pipeline.
 * <p>
 * For every EIS-supported model ID (as enumerated by Elastic), this test feeds the OTel
 * attributes through {@link OpenTelemetryMapper} and then through {@link CostService},
 * asserting that:
 * <ol>
 *   <li>the resolver rewrites the span's provider and model to the underlying Opik canonical
 *   values, and</li>
 *   <li>the resulting span's cost is non-zero (a pricing row exists for the rewritten key).</li>
 * </ol>
 * One known gap is parameterized as well: {@code jina-reranker-v3} has no pricing data yet,
 * so the mapper must still resolve provider/model correctly but cost is expected to be zero.
 * <p>
 * This test is intentionally coarse — it isn't trying to assert specific cost figures, just
 * that nothing in the supported list silently falls through to {@code DEFAULT_COST}. If a
 * future LiteLLM sync removes a row or a new EIS model is added without a JSON entry, this
 * test will surface the regression immediately.
 */
class ElasticInferenceServiceOtelPipelineTest {

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("provideEisModels")
    void eisModelResolvesAndPricesCorrectly(String eisModelId, String expectedProvider, String expectedModel,
            boolean expectNonZeroCost) {
        var attributes = List.of(
                stringAttr("gen_ai.system", "elastic"),
                stringAttr("gen_ai.request.model", eisModelId),
                intAttr("gen_ai.usage.input_tokens", 1000),
                intAttr("gen_ai.usage.output_tokens", 500));

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);
        var span = spanBuilder.build();

        assertThat(span.provider())
                .as("resolver provider for EIS model '%s'", eisModelId)
                .isEqualTo(expectedProvider);
        assertThat(span.model())
                .as("resolver model for EIS model '%s'", eisModelId)
                .isEqualTo(expectedModel);

        BigDecimal cost = CostService.calculateCost(span.model(), span.provider(), span.usage(), span.metadata());
        if (expectNonZeroCost) {
            assertThat(cost)
                    .as("cost for EIS model '%s' (%s/%s)", eisModelId, expectedProvider, expectedModel)
                    .isGreaterThan(BigDecimal.ZERO);
        } else {
            assertThat(cost)
                    .as("cost for EIS model '%s' (no pricing row expected)", eisModelId)
                    .isEqualByComparingTo(BigDecimal.ZERO);
        }
    }

    private static Stream<Arguments> provideEisModels() {
        return Stream.of(
                // --- chat models ---
                Arguments.of("anthropic-claude-4.5-haiku", "anthropic", "claude-4.5-haiku", true),
                Arguments.of("anthropic-claude-4.5-opus", "anthropic", "claude-4.5-opus", true),
                Arguments.of("anthropic-claude-4.5-sonnet", "anthropic", "claude-4.5-sonnet", true),
                Arguments.of("anthropic-claude-4.6-opus", "anthropic", "claude-4.6-opus", true),
                Arguments.of("anthropic-claude-4.6-sonnet", "anthropic", "claude-4.6-sonnet", true),
                Arguments.of("anthropic-claude-4.7-opus", "anthropic", "claude-4.7-opus", true),
                Arguments.of("google-gemini-2.5-flash", "google_ai", "gemini-2.5-flash", true),
                Arguments.of("google-gemini-2.5-flash-lite", "google_ai", "gemini-2.5-flash-lite", true),
                Arguments.of("google-gemini-2.5-pro", "google_ai", "gemini-2.5-pro", true),
                Arguments.of("google-gemini-3-flash", "google_ai", "gemini-3-flash", true),
                Arguments.of("google-gemini-3.1-pro", "google_ai", "gemini-3.1-pro", true),
                Arguments.of("openai-gpt-4.1", "openai", "gpt-4.1", true),
                Arguments.of("openai-gpt-4.1-mini", "openai", "gpt-4.1-mini", true),
                Arguments.of("openai-gpt-5.2", "openai", "gpt-5.2", true),
                Arguments.of("openai-gpt-5.4", "openai", "gpt-5.4", true),
                Arguments.of("openai-gpt-5.4-mini", "openai", "gpt-5.4-mini", true),
                Arguments.of("openai-gpt-5.4-nano", "openai", "gpt-5.4-nano", true),
                Arguments.of("openai-gpt-oss-120b", "openai", "gpt-oss-120b", true),
                Arguments.of("openai-gpt-oss-20b", "openai", "gpt-oss-20b", true),

                // --- embedding models ---
                Arguments.of("jina-clip-v2", "jina_ai", "jina-clip-v2", true),
                // elser_model_2 has no '-' so the resolver no-ops; provider stays "elastic"
                Arguments.of("elser_model_2", "elastic", "elser_model_2", true),
                Arguments.of("jina-embeddings-v3", "jina_ai", "jina-embeddings-v3", true),
                Arguments.of("jina-embeddings-v5-omni-nano", "jina_ai", "jina-embeddings-v5-omni-nano", true),
                Arguments.of("jina-embeddings-v5-omni-small", "jina_ai", "jina-embeddings-v5-omni-small", true),
                Arguments.of("jina-embeddings-v5-text-nano", "jina_ai", "jina-embeddings-v5-text-nano", true),
                Arguments.of("jina-embeddings-v5-text-small", "jina_ai", "jina-embeddings-v5-text-small", true),
                Arguments.of("google-gemini-embedding-001", "google_ai", "gemini-embedding-001", true),
                Arguments.of("google-gemini-embedding-002", "google_ai", "gemini-embedding-002", true),
                Arguments.of("microsoft-multilingual-e5-large", "azure", "multilingual-e5-large", true),
                Arguments.of("openai-text-embedding-3-large", "openai", "text-embedding-3-large", true),
                Arguments.of("openai-text-embedding-3-small", "openai", "text-embedding-3-small", true),

                // --- rerankers ---
                Arguments.of("jina-reranker-v2-base-multilingual", "jina_ai", "jina-reranker-v2-base-multilingual",
                        true),
                // Known gap: pricing for jina-reranker-v3 hasn't been published; mapper must still resolve cleanly.
                Arguments.of("jina-reranker-v3", "jina_ai", "jina-reranker-v3", false));
    }

    private static KeyValue stringAttr(String key, String value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value))
                .build();
    }

    private static KeyValue intAttr(String key, long value) {
        return KeyValue.newBuilder()
                .setKey(key)
                .setValue(AnyValue.newBuilder().setIntValue(value))
                .build();
    }
}
