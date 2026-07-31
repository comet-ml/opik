package com.comet.opik.infrastructure;

import com.google.cloud.vertexai.Transport;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Data
public class LlmProviderClientConfig {

    public record OpenAiClientConfig(String url) {
    }

    public record AnthropicClientConfig(String url, String version) {
    }

    public record VertexAIClientConfig(String scope, Map<String, String> multiRegionApiEndpoints, Transport transport) {

        /**
         * The Vertex AI SDK derives its host from the location as {@code %s-aiplatform.googleapis.com}, which only
         * holds for single-region locations. Multi-region locations resolve to their own hosts, so they have to be set
         * explicitly or the client targets a name that does not exist (e.g. {@code global-aiplatform.googleapis.com}).
         */
        private static final Map<String, String> DEFAULT_MULTI_REGION_API_ENDPOINTS = Map.of(
                "global", "aiplatform.googleapis.com",
                "eu", "aiplatform.eu.rep.googleapis.com",
                "us", "aiplatform.us.rep.googleapis.com");

        /**
         * Configured entries are overlaid on the defaults rather than replacing them, so overriding one location does
         * not silently drop the others. Keys are canonicalised to match the lookup, which uses the canonicalised
         * location.
         */
        public Map<String, String> multiRegionApiEndpoints() {
            if (multiRegionApiEndpoints == null || multiRegionApiEndpoints.isEmpty()) {
                return DEFAULT_MULTI_REGION_API_ENDPOINTS;
            }

            var endpoints = new HashMap<>(DEFAULT_MULTI_REGION_API_ENDPOINTS);
            multiRegionApiEndpoints
                    .forEach((location, endpoint) -> endpoints.put(canonicalLocation(location), endpoint));

            return Map.copyOf(endpoints);
        }

        public static String canonicalLocation(String location) {
            return location.strip().toLowerCase(Locale.ROOT);
        }

        public Transport transport() {
            return transport == null ? Transport.GRPC : transport;
        }
    }

    @Min(1) private Integer maxAttempts;

    @Min(1) private int delayMillis = 500;

    @Positive private Double jitterScale;

    @Positive private Double backoffExp;

    @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    private Duration callTimeout;

    @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    private Duration connectTimeout;

    @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    private Duration readTimeout;

    @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    private Duration writeTimeout;

    private Boolean logRequests;

    private Boolean logResponses;

    @Valid private LlmProviderClientConfig.OpenAiClientConfig openAiClient;

    @Valid private LlmProviderClientConfig.AnthropicClientConfig anthropicClient;

    @Valid private LlmProviderClientConfig.VertexAIClientConfig vertexAIClient;

    private String openRouterUrl;
}
