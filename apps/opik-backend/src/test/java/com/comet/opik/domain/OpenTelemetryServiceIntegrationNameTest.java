package com.comet.opik.domain;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OpenTelemetryServiceIntegrationNameTest {

    private static Stream<Arguments> knownAliases() {
        return Stream.of(
                Arguments.of("openinference.instrumentation.langchain", "OpenInference"),
                Arguments.of("traceloop.sdk", "OpenInference"),
                Arguments.of("opentelemetry.instrumentation.openllmetry", "OpenInference"),
                Arguments.of("openlit.instrumentation", "Logfire"),
                Arguments.of("langfuse", "LangFuse"),
                Arguments.of("livekit.agents", "LiveKit"),
                Arguments.of("pydantic-ai", "Pydantic"),
                Arguments.of("huggingface.smolagents", "Smolagents"));
    }

    private static Stream<Arguments> unknownOrEmptyValues() {
        return Stream.of(
                Arguments.of("unknown.instrumentation", "unknown.instrumentation"),
                Arguments.of("", null),
                Arguments.of("   ", null),
                Arguments.of(null, null));
    }

    @ParameterizedTest(name = "normalize known alias: ''{0}'' -> ''{1}''")
    @MethodSource("knownAliases")
    void normalizeIntegrationNameForKnownAliases(String input, String expected) {
        assertThat(OpenTelemetryServiceImpl.normalizeIntegrationName(input)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "normalize unknown/empty: ''{0}'' -> ''{1}''")
    @MethodSource("unknownOrEmptyValues")
    void normalizeIntegrationNameForUnknownOrEmptyValues(String input, String expected) {
        assertThat(OpenTelemetryServiceImpl.normalizeIntegrationName(input)).isEqualTo(expected);
    }
}
