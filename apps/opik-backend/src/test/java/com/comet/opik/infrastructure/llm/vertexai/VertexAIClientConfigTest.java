package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
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
        var yaml = Files.readString(Path.of(configFile))
                .replaceAll("\\$\\{[^:}]+:-([^}]*)}", "$1")
                .replaceAll("\\$\\{[^}]+}", "placeholder");

        var llmProviderClient = new ObjectMapper(new YAMLFactory())
                .readTree(yaml)
                .get("llmProviderClient");

        var config = new ObjectMapper()
                .convertValue(llmProviderClient, LlmProviderClientConfig.class);

        assertThat(config.getVertexAIClient().multiRegionApiEndpoints())
                .containsExactlyInAnyOrderEntriesOf(EXPECTED_ENDPOINTS);
    }

    @Test
    void fallBackToDefaultsWhenNotConfigured() {
        var config = new LlmProviderClientConfig.VertexAIClientConfig("scope", null);

        assertThat(config.multiRegionApiEndpoints()).containsExactlyInAnyOrderEntriesOf(EXPECTED_ENDPOINTS);
    }
}
