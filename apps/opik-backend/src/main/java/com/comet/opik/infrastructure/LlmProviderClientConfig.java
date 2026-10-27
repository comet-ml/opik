package com.comet.opik.infrastructure;

import com.comet.opik.infrastructure.llm.AnthropicClientConfig;
import com.comet.opik.infrastructure.llm.OpenAiClientConfig;
import com.comet.opik.infrastructure.llm.VertexAIClientConfig;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.fasterxml.jackson.annotation.JsonIgnore;
import io.dropwizard.util.DataSize;
import io.dropwizard.util.DataSizeUnit;
import io.dropwizard.util.Duration;
import io.dropwizard.validation.MaxDataSize;
import io.dropwizard.validation.MaxDuration;
import io.dropwizard.validation.MinDataSize;
import io.dropwizard.validation.MinDuration;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
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
     * Schemes allowed for the media URLs downloaded for Gemini and Vertex AI: {@code plaintext_or_tls} also accepts
     * http; {@code https_only} refuses it. Checked in strict mode only; in every mode, a URL that isn't http or https
     * is refused.
     */
    @NotNull private DestinationGuard.Scheme mediaUrlDestinationScheme;

    /**
     * Time allowed for downloading one media URL that Gemini or Vertex AI receives as inline data, connecting
     * included. At most 5 minutes: every download completes before the provider is called, so a slow or stalled media
     * server must fail the request fast, and 5 minutes still fits the largest size cap (100 MB) at about 350 KB/s.
     */
    @NotNull @MinDuration(value = 1, unit = TimeUnit.MILLISECONDS)
    @MaxDuration(value = 5, unit = TimeUnit.MINUTES)
    private Duration mediaDownloadTimeout;

    /**
     * Most bytes downloaded for all the media of one Gemini or Vertex AI request; they are held in memory, base64
     * encoded, while they are sent inline. Gemini refuses a request with more than about 20 MB of inline data, so
     * downloading more would only be refused.
     */
    @NotNull @MinDataSize(1)
    @MaxDataSize(value = 100, unit = DataSizeUnit.MEGABYTES)
    private DataSize mediaDownloadMaxSize;

    /**
     * Most bytes read from a custom provider's non-2xx response body; the rest is discarded. Only a short message
     * parsed from it reaches callers, so a larger body is never needed.
     */
    @NotNull @MinDataSize(1)
    @MaxDataSize(value = 1, unit = DataSizeUnit.MEBIBYTES)
    private DataSize providerErrorBodyMaxSize;

    /**
     * Longest provider error message passed on to a caller; a longer one is replaced by the generic one. At most 1000
     * characters: provider error messages are one or two sentences.
     */
    @NotNull @Positive @Max(1000) private Integer providerErrorMessageMaxLength;

    /**
     * Most threads of the shared LLM HTTP client. A call made asynchronously, a stream included, holds one from start
     * to end, so there are at least as many as {@link #httpClientMaxRequests}.
     */
    @NotNull @Min(1) @Max(10_000) private Integer httpClientMaxThreads;

    /**
     * How long a thread of the shared LLM HTTP client stays idle before it ends, so the pool shrinks back after a
     * burst.
     */
    @NotNull @MinDuration(value = 1, unit = TimeUnit.SECONDS)
    @MaxDuration(value = 1, unit = TimeUnit.HOURS)
    private Duration httpClientThreadKeepAlive;

    /**
     * Most LLM provider calls made asynchronously, streams included, that run at once on the shared client. Further
     * calls wait in order for one to end; their timeouts start once they run.
     */
    @NotNull @Min(1) @Max(10_000) private Integer httpClientMaxRequests;

    /**
     * Most of {@link #httpClientMaxRequests} that run at once to a single host. Most calls go to a few hosts, such as
     * one provider's API, so it is at most, and by default equal to, the overall limit.
     */
    @NotNull @Min(1) @Max(10_000) private Integer httpClientMaxRequestsPerHost;

    @JsonIgnore
    @AssertTrue(message = "llmProviderClient.httpClientMaxThreads must be at least llmProviderClient.httpClientMaxRequests") public boolean isHttpClientMaxThreadsAtLeastMaxRequests() {
        return httpClientMaxThreads == null || httpClientMaxRequests == null
                || httpClientMaxThreads >= httpClientMaxRequests;
    }

    @JsonIgnore
    @AssertTrue(message = "llmProviderClient.httpClientMaxRequestsPerHost must be at most llmProviderClient.httpClientMaxRequests") public boolean isHttpClientMaxRequestsPerHostWithinMaxRequests() {
        return httpClientMaxRequestsPerHost == null || httpClientMaxRequests == null
                || httpClientMaxRequestsPerHost <= httpClientMaxRequests;
    }
}
