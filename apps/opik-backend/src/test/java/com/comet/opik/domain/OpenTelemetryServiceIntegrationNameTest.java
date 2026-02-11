package com.comet.opik.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class OpenTelemetryServiceIntegrationNameTest {

    @Test
    void testNormalizeIntegrationNameForKnownAliases() {
        assertThat(OpenTelemetryServiceImpl.normalizeIntegrationName("openinference.instrumentation.langchain"))
                .isEqualTo("OpenInference");
        assertThat(OpenTelemetryServiceImpl.normalizeIntegrationName("traceloop.sdk"))
                .isEqualTo("OpenInference");
        assertThat(OpenTelemetryServiceImpl.normalizeIntegrationName("opentelemetry.instrumentation.openllmetry"))
                .isEqualTo("OpenInference");

        assertThat(OpenTelemetryServiceImpl.normalizeIntegrationName("openlit.instrumentation"))
                .isEqualTo("OpenLIT");
        assertThat(OpenTelemetryServiceImpl.normalizeIntegrationName("langfuse"))
                .isEqualTo("LangFuse");
        assertThat(OpenTelemetryServiceImpl.normalizeIntegrationName("livekit.agents"))
                .isEqualTo("LiveKit");
        assertThat(OpenTelemetryServiceImpl.normalizeIntegrationName("pydantic-ai"))
                .isEqualTo("Pydantic");
        assertThat(OpenTelemetryServiceImpl.normalizeIntegrationName("huggingface.smolagents"))
                .isEqualTo("Smolagents");
    }

    @Test
    void testNormalizeIntegrationNameForUnknownOrEmptyValues() {
        assertThat(OpenTelemetryServiceImpl.normalizeIntegrationName("unknown.instrumentation"))
                .isEqualTo("unknown.instrumentation");
        assertThat(OpenTelemetryServiceImpl.normalizeIntegrationName("")).isNull();
        assertThat(OpenTelemetryServiceImpl.normalizeIntegrationName("   ")).isNull();
        assertThat(OpenTelemetryServiceImpl.normalizeIntegrationName(null)).isNull();
    }
}
