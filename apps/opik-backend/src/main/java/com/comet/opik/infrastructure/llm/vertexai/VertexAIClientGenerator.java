package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.common.base.Preconditions;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.UncheckedExecutionException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.vertexai.gemini.VertexAiGeminiChatModel;
import dev.langchain4j.model.vertexai.gemini.VertexAiGeminiStreamingChatModel;
import jakarta.ws.rs.InternalServerErrorException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

@RequiredArgsConstructor
@Slf4j
public class VertexAIClientGenerator implements LlmProviderClientGenerator<ChatModel> {

    /**
     * A {@link VertexAI} owns a gRPC channel and a GAX executor whose core threads never time out, and the langchain4j
     * constructor used below discards the handle, so nothing downstream can close it. Building one per call therefore
     * stranded a thread pool per LLM call. The client is thread-safe and intended to be long-lived, so it is cached and
     * closed on eviction instead.
     */
    private static final Duration CLIENT_EXPIRE_AFTER_ACCESS = Duration.ofHours(1);

    /**
     * Bounds what an unexpected number of distinct keys can hold open. The realistic cardinality is the number of
     * workspaces configured with a Vertex AI key times the number of locations they use, which is far below this.
     */
    private static final long MAX_CACHED_CLIENTS = 100;

    private final @NonNull LlmProviderClientConfig clientConfig;

    private final Cache<ClientKey, VertexAI> clients = CacheBuilder.newBuilder()
            .expireAfterAccess(CLIENT_EXPIRE_AFTER_ACCESS)
            .maximumSize(MAX_CACHED_CLIENTS)
            .<ClientKey, VertexAI>removalListener(notification -> close(notification.getValue()))
            .build();

    /**
     * Identity of a cached client. Only the credentials and the location vary per call — transport, scope and the
     * endpoint map come from {@link LlmProviderClientConfig}, which is fixed for the lifetime of this singleton. The
     * credentials are held as a digest so the cache never retains the service-account key itself.
     */
    private record ClientKey(String credentialsDigest, String location) {
    }

    private ChatModel newVertexAIClient(LlmProviderClientApiConfig apiKey, ChatCompletionRequest request) {

        VertexAI vertexAI = getVertexAI(apiKey);

        GenerationConfig generationConfig = getGenerationConfig(request);

        GenerativeModel generativeModel = getGenerativeModel(request, vertexAI, generationConfig);

        return new VertexAiGeminiChatModel(generativeModel, generationConfig);
    }

    private GenerativeModel getGenerativeModel(ChatCompletionRequest request, VertexAI vertexAI,
            GenerationConfig generationConfig) {
        var vertexAIModelName = VertexAIModelName.byQualifiedName(request.model())
                .orElseThrow(() -> new IllegalArgumentException("Unsupported model: " + request.model()));

        return new GenerativeModel(vertexAIModelName.toString(), vertexAI)
                .withGenerationConfig(generationConfig);
    }

    public StreamingChatModel newVertexAIStreamingClient(@NonNull LlmProviderClientApiConfig apiKey,
            @NonNull ChatCompletionRequest request) {

        VertexAI vertexAI = getVertexAI(apiKey);

        GenerationConfig generationConfig = getGenerationConfig(request);

        GenerativeModel generativeModel = getGenerativeModel(request, vertexAI, generationConfig);

        return new VertexAiGeminiStreamingChatModel(generativeModel, generationConfig);
    }

    private InternalServerErrorException failWithError(Exception e) {
        return new InternalServerErrorException("Failed to create GoogleCredentials", e);
    }

    private GenerationConfig getGenerationConfig(ChatCompletionRequest request) {
        var generationConfig = GenerationConfig.newBuilder();

        Optional.ofNullable(request.temperature())
                .map(Double::floatValue)
                .ifPresent(generationConfig::setTemperature);

        Optional.ofNullable(request.topP())
                .map(Double::floatValue)
                .ifPresent(generationConfig::setTopP);

        Optional.ofNullable(request.stop())
                .ifPresent(values -> values.forEach(generationConfig::addStopSequences));

        Optional.ofNullable(request.presencePenalty())
                .map(Double::floatValue)
                .ifPresent(generationConfig::setPresencePenalty);

        Optional.ofNullable(request.frequencyPenalty())
                .map(Double::floatValue)
                .ifPresent(generationConfig::setFrequencyPenalty);

        Optional.ofNullable(request.maxTokens())
                .ifPresent(generationConfig::setMaxOutputTokens);

        Optional.ofNullable(request.seed())
                .ifPresent(generationConfig::setSeed);

        return generationConfig.build();
    }

    /**
     * The location is free-text in the provider configuration but ends up in the {@code locations/%s} resource path as
     * well as the host, so it has to be canonicalised before either is derived from it. The configured endpoint keys
     * are constrained to the same lower-case form, so both sides of the lookup agree on the key.
     */
    private static String canonicalLocation(String location) {
        return location.strip().toLowerCase(Locale.ROOT);
    }

    private Optional<String> apiEndpointFor(String canonicalLocation) {
        return Optional.ofNullable(clientConfig.getVertexAIClient().multiRegionApiEndpoints().get(canonicalLocation));
    }

    private VertexAI getVertexAI(LlmProviderClientApiConfig config) {
        var location = Optional.ofNullable(config.configuration().get("location"))
                .filter(StringUtils::isNotBlank)
                .map(VertexAIClientGenerator::canonicalLocation);

        var key = new ClientKey(credentialsDigest(config.apiKey()), location.orElse(null));

        try {
            return clients.get(key, () -> buildVertexAI(config.apiKey(), location));
        } catch (ExecutionException | UncheckedExecutionException e) {
            throw failWithError(e.getCause() instanceof Exception cause ? cause : e);
        }
    }

    private VertexAI buildVertexAI(String apiKey, Optional<String> location) throws IOException {
        var credentials = ServiceAccountCredentials.fromStream(
                new ByteArrayInputStream(apiKey.getBytes(StandardCharsets.UTF_8)));

        VertexAI.Builder builder = new VertexAI.Builder();

        location.ifPresent(canonicalLocation -> {
            builder.setLocation(canonicalLocation);
            apiEndpointFor(canonicalLocation).ifPresent(builder::setApiEndpoint);
        });

        return builder
                .setProjectId(credentials.getProjectId())
                .setCredentials(credentials.createScoped(clientConfig.getVertexAIClient().scope()))
                .setTransport(clientConfig.getVertexAIClient().transport())
                .build();
    }

    /**
     * The service-account key is the caller's secret, so only a digest of it is kept as part of the cache key. Rotating
     * a key yields a different digest and therefore a new client, leaving the previous one to be closed on eviction.
     */
    private static String credentialsDigest(String apiKey) {
        return Hashing.sha256().hashString(apiKey, StandardCharsets.UTF_8).toString();
    }

    /**
     * Eviction is the only place a client is closed, and it must not take a request down with it: the entry is only
     * evicted after {@link #CLIENT_EXPIRE_AFTER_ACCESS} without a single lookup, which no in-flight call outlives.
     */
    private static void close(VertexAI client) {
        if (client == null) {
            return;
        }

        try {
            client.close();
        } catch (Exception e) {
            log.warn("Failed to close Vertex AI client", e);
        }
    }

    @Override
    public ChatModel generate(@NonNull LlmProviderClientApiConfig config, Object... params) {
        Preconditions.checkArgument(params.length >= 1, "Expected at least 1 parameter, got " + params.length);
        ChatCompletionRequest request = (ChatCompletionRequest) Objects.requireNonNull(params[0],
                "ChatCompletionRequest is required");

        return newVertexAIClient(config, request);
    }

    @Override
    public ChatModel generateChat(@NonNull LlmProviderClientApiConfig apiKey,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        var requestBuilder = ChatCompletionRequest.builder()
                .model(modelParameters.name());

        Optional.ofNullable(modelParameters.temperature()).ifPresent(requestBuilder::temperature);
        Optional.ofNullable(modelParameters.seed()).ifPresent(requestBuilder::seed);

        return newVertexAIClient(apiKey, requestBuilder.build());
    }
}
