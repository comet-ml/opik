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
 * End-to-end smoke test for the generic {@code "google"} provider ingestion pipeline (PydanticAI /
 * google-genai over OTel). PydanticAI emits {@code gen_ai.system = "google"} for both Google
 * backends, which on its own matches no pricing row (cost stays 0 — the reported bug). This test
 * feeds the OTel attributes through {@link OpenTelemetryMapper} and then {@link CostService},
 * asserting that:
 * <ol>
 *   <li>{@code server.address} disambiguates the provider into {@code google_vertexai} /
 *   {@code google_ai}, and</li>
 *   <li>the resulting span's cost is non-zero (a pricing row exists for the resolved key).</li>
 * </ol>
 */
class GoogleProviderOtelPipelineTest {

    @ParameterizedTest(name = "[{index}] {1} @ {2} -> {3}")
    @MethodSource("provideGoogleCases")
    void googleProviderResolvesAndPricesCorrectly(String ignoredLabel, String model, String serverAddress,
            String expectedProvider) {
        var attributes = List.of(
                stringAttr("gen_ai.system", "google"),
                stringAttr("gen_ai.request.model", model),
                stringAttr("server.address", serverAddress),
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
                .as("resolved provider for model '%s' @ '%s'", model, serverAddress)
                .isEqualTo(expectedProvider);

        BigDecimal cost = CostService.calculateCost(span.model(), span.provider(), span.usage(), span.metadata());
        assertThat(cost)
                .as("cost for '%s' (%s)", model, expectedProvider)
                .isGreaterThan(BigDecimal.ZERO);
    }

    private static Stream<Arguments> provideGoogleCases() {
        return Stream.of(
                Arguments.of("vertex flash-lite", "gemini-2.5-flash-lite",
                        "us-east1-aiplatform.googleapis.com", "google_vertexai"),
                Arguments.of("vertex flash", "gemini-2.5-flash",
                        "us-central1-aiplatform.googleapis.com", "google_vertexai"),
                Arguments.of("gemini-api flash-lite", "gemini-2.5-flash-lite",
                        "generativelanguage.googleapis.com", "google_ai"),
                Arguments.of("gemini-api pro", "gemini-2.5-pro",
                        "generativelanguage.googleapis.com", "google_ai"));
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
