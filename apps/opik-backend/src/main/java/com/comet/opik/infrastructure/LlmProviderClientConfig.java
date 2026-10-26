package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.llm.AnthropicClientConfig;
import com.comet.opik.infrastructure.llm.OpenAiClientConfig;
import com.comet.opik.infrastructure.llm.VertexAIClientConfig;
import com.comet.opik.infrastructure.net.DestinationGuard;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

import java.util.concurrent.TimeUnit;

@Data
public class LlmProviderClientConfig {

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

    @Valid private OpenAiClientConfig openAiClient;

    @Valid private AnthropicClientConfig anthropicClient;

    @Valid @NotNull private VertexAIClientConfig vertexAIClient;

    private String openRouterUrl;

    @NotBlank private String openRouterDecisionsUrl;

    /**
     * Destination policy for user-configured provider base URLs: {@code strict} refuses private and
     * internal destinations, {@code relaxed} allows them for self-hosted providers on internal networks.
     */
    @NotNull private DestinationGuard.Mode destinationGuard;

    /**
     * Schemes allowed for user-configured provider base URLs: {@code plaintext_or_tls} also accepts http, which
     * self-hosted model servers commonly use; {@code https_only} refuses it.
     */
    @NotNull private DestinationGuard.Scheme destinationScheme;

    /**
     * Destination policy for the HEAD request that detects the MIME type of an extensionless video URL. The URL
     * comes from any caller's message, and a refusal only loses the hint.
     */
    @NotNull private DestinationGuard.Mode videoUrlDestinationGuard;
}
