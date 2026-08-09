package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.common.annotations.VisibleForTesting;
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
import java.util.function.Consumer;

@Slf4j
public class VertexAIClientGenerator implements LlmProviderClientGenerator<ChatModel> {

    // One VertexAI per (credentials, location); idle-only eviction so we never close a client mid-call.
    private final @NonNull LlmProviderClientConfig clientConfig;
    private final Cache<ClientKey, VertexAI> clients;

    public VertexAIClientGenerator(@NonNull LlmProviderClientConfig clientConfig) {
        this(clientConfig, clientConfig.getVertexAIClient().clientIdleTimeout().toJavaDuration(),
                VertexAIClientGenerator::close);
    }

    @VisibleForTesting
    VertexAIClientGenerator(@NonNull LlmProviderClientConfig clientConfig, @NonNull Duration clientIdleTtl,
            @NonNull Consumer<VertexAI> onEvict) {
        this.clientConfig = clientConfig;
        this.clients = CacheBuilder.newBuilder()
                .expireAfterAccess(clientIdleTtl)
                .<ClientKey, VertexAI>removalListener(notification -> onEvict.accept(notification.getValue()))
                .build();
    }

    // credentialsDigest, not the raw key, so the cache never retains the service-account secret.
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

    private static String credentialsDigest(String apiKey) {
        return Hashing.sha256().hashString(apiKey, StandardCharsets.UTF_8).toString();
    }

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

    @VisibleForTesting
    void invalidateAllClients() {
        clients.invalidateAll();
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
