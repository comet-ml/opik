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
import java.util.ArrayList;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end smoke test for the OTel provider ingestion pipeline: instrumentations emit the
 * provider via {@code gen_ai.system} (deprecated) or {@code gen_ai.provider.name} using the
 * OTel semantic-convention vocabulary, which differs from the Opik price-table vocabulary.
 * A value that survives ingestion unmapped matches no pricing row and the span silently costs 0.
 * <p>
 * This feeds OTel attributes through {@link OpenTelemetryMapper} and then {@link CostService},
 * asserting for each case that:
 * <ol>
 *   <li>the provider resolves to the canonical Opik name — either by a 1:1 alias
 *   ({@link com.comet.opik.domain.mapping.otel.GenAiProviderAliasResolver}) or, for the
 *   deliberately ambiguous values, by {@code server.address}
 *   ({@link com.comet.opik.domain.mapping.otel.GoogleProviderResolver}), and</li>
 *   <li>the resulting span's cost is non-zero, i.e. a pricing row exists for the resolved key.</li>
 * </ol>
 *
 * @see <a href="https://opentelemetry.io/docs/specs/semconv/registry/attributes/gen-ai/">OTel GenAI attribute registry</a>
 */
class OtelProviderCostPipelineTest {

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("provideProviderCases")
    void providerResolvesAndPricesCorrectly(String ignoredLabel, String providerAttribute, String system,
            String model, String serverAddress, String expectedProvider) {
        var attributes = new ArrayList<KeyValue>();
        attributes.add(stringAttr(providerAttribute, system));
        attributes.add(stringAttr("gen_ai.request.model", model));
        attributes.add(intAttr("gen_ai.usage.input_tokens", 1000));
        attributes.add(intAttr("gen_ai.usage.output_tokens", 500));
        if (serverAddress != null) {
            attributes.add(stringAttr("server.address", serverAddress));
        }

        var spanBuilder = Span.builder()
                .id(UUID.randomUUID())
                .traceId(UUID.randomUUID())
                .projectId(UUID.randomUUID())
                .startTime(Instant.now());

        OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);
        var span = spanBuilder.build();

        assertThat(span.provider())
                .as("resolved provider for '%s' (model '%s' @ '%s')", system, model, serverAddress)
                .isEqualTo(expectedProvider);

        BigDecimal cost = CostService.calculateCost(span.model(), span.provider(), span.usage(), span.metadata());
        assertThat(cost)
                .as("cost for '%s' (%s)", model, expectedProvider)
                .isGreaterThan(BigDecimal.ZERO);
    }

    private static Stream<Arguments> provideProviderCases() {
        return Stream.of(
                // --- Ambiguous values: only server.address tells the two Google backends apart ---
                Arguments.of("google + vertex host", "gen_ai.system", "google", "gemini-2.5-flash-lite",
                        "us-east1-aiplatform.googleapis.com", "google_vertexai"),
                Arguments.of("google + gemini-api host", "gen_ai.system", "google", "gemini-2.5-flash-lite",
                        "generativelanguage.googleapis.com", "google_ai"),
                Arguments.of("gcp.gen_ai + vertex host", "gen_ai.system", "gcp.gen_ai", "gemini-2.5-flash",
                        "us-central1-aiplatform.googleapis.com", "google_vertexai"),
                Arguments.of("gcp.gen_ai, no host defaults to gemini api", "gen_ai.system", "gcp.gen_ai",
                        "gemini-2.5-flash", null, "google_ai"),

                // --- OPIK-7717: the reported case. Standard OTel value, no host needed ---
                Arguments.of("vertex_ai (OPIK-7717)", "gen_ai.system", "vertex_ai", "gemini-3.1-flash-lite",
                        null, "google_vertexai"),

                // --- Unambiguous 1:1 aliases, current semconv spelling ---
                Arguments.of("gcp.vertex_ai", "gen_ai.system", "gcp.vertex_ai", "gemini-3.1-flash-lite",
                        null, "google_vertexai"),
                Arguments.of("gcp.gemini", "gen_ai.system", "gcp.gemini", "gemini-2.5-flash", null, "google_ai"),
                Arguments.of("aws.bedrock", "gen_ai.system", "aws.bedrock",
                        "anthropic.claude-3-5-sonnet-20241022-v2:0", null, "bedrock"),
                Arguments.of("azure.ai.openai", "gen_ai.system", "azure.ai.openai", "gpt-4o", null, "azure"),
                Arguments.of("az.ai.openai (pre-rename spelling)", "gen_ai.system", "az.ai.openai", "gpt-4o",
                        null, "azure"),
                Arguments.of("mistral_ai", "gen_ai.system", "mistral_ai", "mistral-large-latest", null, "mistral"),
                Arguments.of("x_ai", "gen_ai.system", "x_ai", "grok-3", null, "xai"),

                // --- Same resolution via the current attribute that replaced gen_ai.system ---
                Arguments.of("gen_ai.provider.name gcp.vertex_ai", "gen_ai.provider.name", "gcp.vertex_ai",
                        "gemini-3.1-flash-lite", null, "google_vertexai"),
                Arguments.of("gen_ai.provider.name aws.bedrock", "gen_ai.provider.name", "aws.bedrock",
                        "anthropic.claude-3-5-sonnet-20241022-v2:0", null, "bedrock"),
                Arguments.of("gen_ai.provider.name gcp.gen_ai + vertex host", "gen_ai.provider.name", "gcp.gen_ai",
                        "gemini-2.5-flash", "us-central1-aiplatform.googleapis.com", "google_vertexai"),

                // --- Canonical values already in the price vocabulary must survive untouched ---
                Arguments.of("openai passes through", "gen_ai.system", "openai", "gpt-4o", null, "openai"),
                Arguments.of("anthropic passes through", "gen_ai.system", "anthropic", "claude-sonnet-4-5",
                        null, "anthropic"),
                Arguments.of("bedrock passes through", "gen_ai.system", "bedrock",
                        "anthropic.claude-3-5-sonnet-20241022-v2:0", null, "bedrock"));
    }

    private static KeyValue stringAttr(String key, String value) {
        return KeyValue.newBuilder().setKey(key)
                .setValue(AnyValue.newBuilder().setStringValue(value)).build();
    }

    private static KeyValue intAttr(String key, long value) {
        return KeyValue.newBuilder().setKey(key)
                .setValue(AnyValue.newBuilder().setIntValue(value)).build();
    }
}
