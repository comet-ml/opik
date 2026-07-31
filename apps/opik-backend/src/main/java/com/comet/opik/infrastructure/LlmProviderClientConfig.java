package com.comet.opik.infrastructure;

import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.Map;
import java.util.concurrent.TimeUnit;

@Data
public class LlmProviderClientConfig {

    public record OpenAiClientConfig(String url) {
    }

    public record AnthropicClientConfig(String url, String version) {
    }

    public record VertexAIClientConfig(String scope, Map<String, String> multiRegionApiEndpoints) {

        /**
         * The Vertex AI SDK derives its host from the location as {@code %s-aiplatform.googleapis.com}, which only
         * holds for single-region locations. Multi-region locations resolve to their own hosts, so they have to be set
         * explicitly or the client targets a name that does not exist (e.g. {@code global-aiplatform.googleapis.com}).
         */
        private static final Map<String, String> DEFAULT_MULTI_REGION_API_ENDPOINTS = Map.of(
                "global", "aiplatform.googleapis.com",
                "eu", "aiplatform.eu.rep.googleapis.com",
                "us", "aiplatform.us.rep.googleapis.com");

        public Map<String, String> multiRegionApiEndpoints() {
            return multiRegionApiEndpoints == null || multiRegionApiEndpoints.isEmpty()
                    ? DEFAULT_MULTI_REGION_API_ENDPOINTS
                    : multiRegionApiEndpoints;
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
