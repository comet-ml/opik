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
import com.google.common.hash.Hashing;
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
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class VertexAIClientGenerator implements LlmProviderClientGenerator<ChatModel> {

    // One VertexAI per (credentials, location), reused across calls.
    private final @NonNull LlmProviderClientConfig clientConfig;
    private final Map<ClientKey, VertexAI> clients = new ConcurrentHashMap<>();

    public VertexAIClientGenerator(@NonNull LlmProviderClientConfig clientConfig) {
        this.clientConfig = clientConfig;
    }

    // Key on a digest of the credential, not the raw JSON.
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

        return clients.computeIfAbsent(key, ignored -> buildVertexAI(config.apiKey(), location));
    }

    private VertexAI buildVertexAI(String apiKey, Optional<String> location) {
        try {
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
        } catch (IOException e) {
            throw failWithError(e);
        }
    }

    private static String credentialsDigest(String apiKey) {
        return Hashing.sha256().hashString(apiKey, StandardCharsets.UTF_8).toString();
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
