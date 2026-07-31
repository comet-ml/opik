package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.cloud.vertexai.Transport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Vertex AI multi-region endpoints are configurable")
class VertexAIClientConfigTest {

    private static final Map<String, String> EXPECTED_ENDPOINTS = Map.of(
            "global", "aiplatform.googleapis.com",
            "eu", "aiplatform.eu.rep.googleapis.com",
            "us", "aiplatform.us.rep.googleapis.com");

    /**
     * Guards the shipped YAML rather than only the Java defaults: a typo in the key name would otherwise leave the
     * endpoints silently falling back to the hardcoded defaults, which is exactly what making them configurable was
     * meant to avoid.
     */
    @ParameterizedTest
    @ValueSource(strings = {"config.yml", "src/test/resources/config-test.yml"})
    void areReadFromTheShippedConfiguration(String configFile) throws Exception {
        assertThat(vertexAIClientConfig(configFile).multiRegionApiEndpoints())
                .containsExactlyInAnyOrderEntriesOf(EXPECTED_ENDPOINTS);
    }

    private static LlmProviderClientConfig.VertexAIClientConfig vertexAIClientConfig(String configFile)
            throws Exception {
        var yaml = Files.readString(Path.of(configFile))
                .replaceAll("\\$\\{[^:}]+:-([^}]*)}", "$1")
                .replaceAll("\\$\\{[^}]+}", "placeholder");

        var llmProviderClient = new ObjectMapper(new YAMLFactory())
                .readTree(yaml)
                .get("llmProviderClient");

        return new ObjectMapper()
                .convertValue(llmProviderClient, LlmProviderClientConfig.class)
                .getVertexAIClient();
    }

    @Test
    void fallBackToDefaultsWhenNotConfigured() {
        var config = new LlmProviderClientConfig.VertexAIClientConfig("scope", null, null);

        assertThat(config.multiRegionApiEndpoints()).containsExactlyInAnyOrderEntriesOf(EXPECTED_ENDPOINTS);
    }

    /**
     * The transport is only configurable so the WireMock-based tests can use REST; production must keep defaulting to
     * gRPC whether the key is absent or the whole block is.
     */
    @Test
    void defaultTransportToGrpcWhenNotConfigured() {
        var config = new LlmProviderClientConfig.VertexAIClientConfig("scope", null, null);

        assertThat(config.transport()).isEqualTo(Transport.GRPC);
    }

    @ParameterizedTest
    @ValueSource(strings = {"config.yml"})
    void keepGrpcTransportInProductionConfiguration(String configFile) throws Exception {
        assertThat(vertexAIClientConfig(configFile).transport()).isEqualTo(Transport.GRPC);
    }
}
