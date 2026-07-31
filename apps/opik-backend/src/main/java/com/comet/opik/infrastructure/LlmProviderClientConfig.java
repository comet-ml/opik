package com.comet.opik.infrastructure;

import com.google.cloud.vertexai.Transport;
import com.google.common.base.Preconditions;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Builder;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;

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

    @Builder(toBuilder = true)
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
         * Resolves the endpoint map once, at construction, so the merge is not repeated on every lookup. Configured
         * entries are overlaid on the defaults rather than replacing them, so overriding one location does not silently
         * drop the others, and their keys are canonicalised to match the lookup.
         */
        public VertexAIClientConfig {
            multiRegionApiEndpoints = resolveMultiRegionApiEndpoints(multiRegionApiEndpoints);
            transport = transport == null ? Transport.GRPC : transport;
        }

        private static Map<String, String> resolveMultiRegionApiEndpoints(Map<String, String> configured) {
            if (configured == null || configured.isEmpty()) {
                return DEFAULT_MULTI_REGION_API_ENDPOINTS;
            }

            var endpoints = new HashMap<>(DEFAULT_MULTI_REGION_API_ENDPOINTS);

            configured.forEach((location, endpoint) -> {
                Preconditions.checkArgument(StringUtils.isNotBlank(location),
                        "Vertex AI multiRegionApiEndpoints contains a blank location");
                Preconditions.checkArgument(StringUtils.isNotBlank(endpoint),
                        "Vertex AI multiRegionApiEndpoints has a blank endpoint for location '%s'", location);

                endpoints.put(canonicalLocation(location), endpoint);
            });

            return Map.copyOf(endpoints);
        }

        public static String canonicalLocation(String location) {
            return location.strip().toLowerCase(Locale.ROOT);
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
