package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import com.comet.opik.infrastructure.llm.vertexai.internal.VertexAiGeminiChatModelCustom;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.vertexai.Transport;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.common.base.Preconditions;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.vertexai.VertexAiGeminiStreamingChatModel;
import jakarta.ws.rs.InternalServerErrorException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters;

@RequiredArgsConstructor
@Slf4j
public class VertexAIClientGenerator implements LlmProviderClientGenerator<ChatModel> {

    private final @NonNull LlmProviderClientConfig clientConfig;

    private ChatModel newVertexAIClient(@NonNull LlmProviderClientApiConfig apiKey,
            @NonNull ChatCompletionRequest request) {

        VertexAI vertexAI = getVertexAI(apiKey);

        GenerationConfig generationConfig = getGenerationConfig(request);

        GenerativeModel generativeModel = getGenerativeModel(request, vertexAI, generationConfig);

        return new VertexAiGeminiChatModelCustom(generativeModel, generationConfig);
    }

    private GenerativeModel getGenerativeModel(@NotNull ChatCompletionRequest request, VertexAI vertexAI,
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

    private GenerationConfig getGenerationConfig(@NotNull ChatCompletionRequest request) {
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

    private VertexAI getVertexAI(@NotNull LlmProviderClientApiConfig config) {
        try {
            var credentials = ServiceAccountCredentials.fromStream(
                    new ByteArrayInputStream(config.apiKey().getBytes(StandardCharsets.UTF_8)));

            VertexAI.Builder builder = new VertexAI.Builder();

            Optional.ofNullable(config.configuration().get("location"))
                    .ifPresent(builder::setLocation);

            return builder
                    .setProjectId(credentials.getProjectId())
                    .setCredentials(credentials.createScoped(clientConfig.getVertexAIClient().scope()))
                    .setTransport(Transport.REST)
                    .build();

        } catch (Exception e) {
            throw failWithError(e);
        }
    }

    @Override
    public ChatModel generate(LlmProviderClientApiConfig config, Object... params) {
        Preconditions.checkArgument(params.length >= 1, "Expected at least 1 parameter, got " + params.length);
        ChatCompletionRequest request = (ChatCompletionRequest) Objects.requireNonNull(params[0],
                "ChatCompletionRequest is required");

        return newVertexAIClient(config, request);
    }

    @Override
    public ChatModel generateChat(LlmProviderClientApiConfig apiKey,
            LlmAsJudgeModelParameters modelParameters) {
        return newVertexAIClient(apiKey, ChatCompletionRequest.builder()
                .model(modelParameters.name())
                .temperature(modelParameters.temperature())
                .build());
    }
}
