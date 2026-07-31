package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.google.cloud.vertexai.Transport;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    @ParameterizedTest
    @NullAndEmptySource
    void fallBackToDefaultsWhenNotConfigured(Map<String, String> configured) {
        var config = LlmProviderClientConfig.VertexAIClientConfig.builder()
                .scope("scope")
                .multiRegionApiEndpoints(configured)
                .build();

        assertThat(config.multiRegionApiEndpoints()).containsExactlyInAnyOrderEntriesOf(EXPECTED_ENDPOINTS);
    }

    /**
     * Overriding one location must not drop the others: the configured map is overlaid on the defaults, so a partial
     * override cannot silently leave the remaining multi-region locations resolving to a derived host.
     */
    @Test
    void overlayConfiguredEndpointsOnTheDefaults() {
        var config = LlmProviderClientConfig.VertexAIClientConfig.builder()
                .scope("scope")
                .multiRegionApiEndpoints(Map.of("global", "custom.googleapis.com"))
                .build();

        assertThat(config.multiRegionApiEndpoints())
                .containsEntry("global", "custom.googleapis.com")
                .containsEntry("eu", EXPECTED_ENDPOINTS.get("eu"))
                .containsEntry("us", EXPECTED_ENDPOINTS.get("us"));
    }

    /**
     * The lookup happens on a canonicalised location, so keys have to be canonicalised too or a configured
     * {@code Global:} would never be matched and would silently fall back to the derived host.
     */
    @ParameterizedTest
    @ValueSource(strings = {"GLOBAL", "Global", "  global  "})
    void canonicaliseConfiguredKeys(String configuredKey) {
        var config = LlmProviderClientConfig.VertexAIClientConfig.builder()
                .scope("scope")
                .multiRegionApiEndpoints(Map.of(configuredKey, "custom.googleapis.com"))
                .build();

        assertThat(config.multiRegionApiEndpoints()).containsEntry("global", "custom.googleapis.com");
    }

    /**
     * A YAML entry such as {@code global:} with no value deserialises to a null, which would otherwise surface as an
     * opaque {@code NullPointerException} while building the endpoint map instead of naming the offending config.
     */
    @Test
    void rejectBlankEndpointEntries() {
        var blankLocation = new HashMap<String, String>();
        blankLocation.put(null, "custom.googleapis.com");

        var blankEndpoint = new HashMap<String, String>();
        blankEndpoint.put("global", null);

        assertThatThrownBy(() -> endpointsFor(blankLocation))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("blank location");
        assertThatThrownBy(() -> endpointsFor(blankEndpoint))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("global");
    }

    private static Map<String, String> endpointsFor(Map<String, String> configured) {
        return LlmProviderClientConfig.VertexAIClientConfig.builder()
                .scope("scope")
                .multiRegionApiEndpoints(configured)
                .build()
                .multiRegionApiEndpoints();
    }

    /**
     * The transport is only configurable so the WireMock-based tests can use REST; production must keep defaulting to
     * gRPC whether the key is absent or the whole block is.
     */
    @Test
    void defaultTransportToGrpcWhenNotConfigured() {
        var config = LlmProviderClientConfig.VertexAIClientConfig.builder().scope("scope").build();

        assertThat(config.transport()).isEqualTo(Transport.GRPC);
    }

    @Test
    void keepGrpcTransportInProductionConfiguration() throws Exception {
        assertThat(vertexAIClientConfig("config.yml").transport()).isEqualTo(Transport.GRPC);
    }
}
