package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.llm.AnthropicClientConfig;
import com.comet.opik.infrastructure.llm.OpenAiClientConfig;
import com.comet.opik.infrastructure.llm.VertexAIClientConfig;
import com.comet.opik.infrastructure.net.DestinationGuard;
import io.dropwizard.util.DataSize;
import io.dropwizard.util.DataSizeUnit;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDataSize;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDataSize;
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
     * Destination policy for media URLs in a caller's message: the HEAD request that detects the MIME type of an
     * extensionless video URL, and the download of the media URLs Gemini and Vertex AI would otherwise fetch
     * themselves. The URL comes from any caller's message, or from trace data for online scoring.
     */
    @NotNull private DestinationGuard.Mode videoUrlDestinationGuard;

    /**
     * Time allowed for downloading one media URL that Gemini or Vertex AI receives as inline data, connecting
     * included. At most 5 minutes: every download completes before the provider is called, so a slow or stalled media
     * server must fail the request fast, and 5 minutes still fits the largest size cap (1 GiB) at about 3.5 MB/s.
     */
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 5, unit = TimeUnit.MINUTES)
    private Duration mediaDownloadTimeout;

    /** Largest media file downloaded for Gemini or Vertex AI; it is held in memory while it is sent inline. */
    @NotNull @MinDataSize(1)
    @MaxDataSize(value = 1, unit = DataSizeUnit.GIBIBYTES)
    private DataSize mediaDownloadMaxSize;

    /** Longest provider error message passed on to a caller; a longer one is replaced by the generic one. */
    @NotNull @Positive private Integer providerErrorMessageMaxLength;
}
