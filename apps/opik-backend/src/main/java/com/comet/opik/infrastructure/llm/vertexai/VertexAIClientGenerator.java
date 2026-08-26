package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.GeminiThinkingParams;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.common.base.Preconditions;
import dev.langchain4j.model.chat.ChatModel;
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

@Slf4j
public class VertexAIClientGenerator implements LlmProviderClientGenerator<ChatModel> {

    private final @NonNull LlmProviderClientConfig clientConfig;

    public VertexAIClientGenerator(@NonNull LlmProviderClientConfig clientConfig) {
        this.clientConfig = clientConfig;
    }

    CloseableVertexAiChatModel newVertexAIClient(LlmProviderClientApiConfig apiKey, ChatCompletionRequest request) {
        return buildOwnedClient(apiKey, request,
                (generativeModel, generationConfig, vertexAI) -> new CloseableVertexAiChatModel(
                        new VertexAiGeminiChatModel(generativeModel, generationConfig), vertexAI));
    }

    private GenerativeModel getGenerativeModel(ChatCompletionRequest request, VertexAI vertexAI,
            GenerationConfig generationConfig) {
        var vertexAIModelName = VertexAIModelName.byQualifiedName(request.model())
                .orElseThrow(() -> new IllegalArgumentException("Unsupported model: " + request.model()));

        return new GenerativeModel(vertexAIModelName.toString(), vertexAI)
                .withGenerationConfig(generationConfig);
    }

    CloseableVertexAiStreamingChatModel newVertexAIStreamingClient(@NonNull LlmProviderClientApiConfig apiKey,
            @NonNull ChatCompletionRequest request) {
        return buildOwnedClient(apiKey, request,
                (generativeModel, generationConfig, vertexAI) -> new CloseableVertexAiStreamingChatModel(
                        new VertexAiGeminiStreamingChatModel(generativeModel, generationConfig), vertexAI));
    }

    // Fresh VertexAI per call, handed to the wrapper that owns and closes it; closed here if setup fails first.
    private <T> T buildOwnedClient(LlmProviderClientApiConfig apiKey, ChatCompletionRequest request,
            OwnedClientFactory<T> factory) {
        VertexAI vertexAI = buildVertexAI(apiKey);
        try {
            GenerationConfig generationConfig = getGenerationConfig(request);
            GenerativeModel generativeModel = getGenerativeModel(request, vertexAI, generationConfig);
            return factory.create(generativeModel, generationConfig, vertexAI);
        } catch (RuntimeException e) {
            closeSuppressing(vertexAI, e);
            throw e;
        }
    }

    @FunctionalInterface
    private interface OwnedClientFactory<T> {
        T create(GenerativeModel generativeModel, GenerationConfig generationConfig, VertexAI vertexAI);
    }

    private InternalServerErrorException failWithError(Exception e) {
        return new InternalServerErrorException("Failed to create GoogleCredentials", e);
    }

    // Close a client we built but couldn't hand to a wrapping owner, so it can't outlive the failure.
    private static void closeSuppressing(VertexAI vertexAI, RuntimeException failure) {
        try {
            vertexAI.close();
        } catch (Exception e) {
            failure.addSuppressed(e);
        }
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

        thinkingConfig(GeminiThinkingParams.from(request.customParameters()))
                .ifPresent(generationConfig::setThinkingConfig);

        return generationConfig.build();
    }

    /**
     * Vertex's {@code ThinkingConfig} has no level field, so a level is translated into the budget it maps to.
     */
    private static Optional<GenerationConfig.ThinkingConfig> thinkingConfig(GeminiThinkingParams params) {
        if (params.isAbsent()) {
            return Optional.empty();
        }

        var thinkingConfig = GenerationConfig.ThinkingConfig.newBuilder();

        Optional.ofNullable(params.budgetForLevel()).ifPresent(thinkingConfig::setThinkingBudget);
        Optional.ofNullable(params.includeThoughts()).ifPresent(thinkingConfig::setIncludeThoughts);

        return Optional.of(thinkingConfig.build());
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

    private VertexAI buildVertexAI(LlmProviderClientApiConfig config) {
        var location = Optional.ofNullable(config.configuration().get("location"))
                .filter(StringUtils::isNotBlank)
                .map(VertexAIClientGenerator::canonicalLocation);

        return buildVertexAI(config.apiKey(), location);
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

        // Round-tripped through the request so the generation config is derived in one place for both paths.
        Optional.ofNullable(modelParameters.customParameters())
                .map(customParameters -> JsonUtils.getMapper()
                        .convertValue(customParameters, new TypeReference<Map<String, Object>>() {
                        }))
                .ifPresent(requestBuilder::customParameters);

        return newVertexAIClient(apiKey, requestBuilder.build());
    }
}
