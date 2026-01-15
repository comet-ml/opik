package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.common.base.Preconditions;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.vertexai.gemini.VertexAiGeminiChatModel;
import dev.langchain4j.model.vertexai.gemini.VertexAiGeminiStreamingChatModel;
import jakarta.ws.rs.InternalServerErrorException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

@Slf4j
public class VertexAIClientGenerator implements LlmProviderClientGenerator<ChatModel> {

    private final @NonNull LlmProviderClientConfig clientConfig;
    private final @NonNull ChatModelListener chatModelListener;

    public VertexAIClientGenerator(@NonNull LlmProviderClientConfig clientConfig,
            @NonNull ChatModelListener chatModelListener) {
        this.clientConfig = clientConfig;
        this.chatModelListener = chatModelListener;
    }

    private ChatModel newVertexAIClient(LlmProviderClientApiConfig config, ChatCompletionRequest request) {
        var credentials = getCredentials(config);
        var vertexAIModelName = VertexAIModelName.byQualifiedName(request.model())
                .orElseThrow(() -> new IllegalArgumentException("Unsupported model: " + request.model()));

        var builder = VertexAiGeminiChatModel.builder()
                .project(credentials.getProjectId())
                .location(config.configuration().get("location"))
                .modelName(vertexAIModelName.toString())
                .credentials(credentials.createScoped(clientConfig.getVertexAIClient().scope()))
                .logRequests(clientConfig.getLogRequests())
                .logResponses(clientConfig.getLogResponses());

        // Add optional parameters from request
        Optional.ofNullable(request.temperature())
                .map(Double::floatValue)
                .ifPresent(builder::temperature);
        Optional.ofNullable(request.topP())
                .map(Double::floatValue)
                .ifPresent(builder::topP);
        Optional.ofNullable(request.maxTokens())
                .ifPresent(builder::maxOutputTokens);
        Optional.ofNullable(request.seed())
                .ifPresent(builder::seed);

        // Add OpenTelemetry instrumentation listener
        if (chatModelListener != null) {
            builder.listeners(List.of(chatModelListener));
        }

        return builder.build();
    }

    public StreamingChatModel newVertexAIStreamingClient(@NonNull LlmProviderClientApiConfig config,
            @NonNull ChatCompletionRequest request) {
        var credentials = getCredentials(config);
        var vertexAIModelName = VertexAIModelName.byQualifiedName(request.model())
                .orElseThrow(() -> new IllegalArgumentException("Unsupported model: " + request.model()));

        var builder = VertexAiGeminiStreamingChatModel.builder()
                .project(credentials.getProjectId())
                .location(config.configuration().get("location"))
                .modelName(vertexAIModelName.toString())
                .credentials(credentials.createScoped(clientConfig.getVertexAIClient().scope()))
                .logRequests(clientConfig.getLogRequests())
                .logResponses(clientConfig.getLogResponses());

        // Add optional parameters from request
        Optional.ofNullable(request.temperature())
                .map(Double::floatValue)
                .ifPresent(builder::temperature);
        Optional.ofNullable(request.topP())
                .map(Double::floatValue)
                .ifPresent(builder::topP);
        Optional.ofNullable(request.maxTokens())
                .ifPresent(builder::maxOutputTokens);

        // Add OpenTelemetry instrumentation listener
        if (chatModelListener != null) {
            builder.listeners(List.of(chatModelListener));
        }

        return builder.build();
    }

    private ServiceAccountCredentials getCredentials(LlmProviderClientApiConfig config) {
        try {
            return ServiceAccountCredentials.fromStream(
                    new ByteArrayInputStream(config.apiKey().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new InternalServerErrorException("Failed to create GoogleCredentials", e);
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
    public ChatModel generateChat(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        var credentials = getCredentials(config);
        var vertexAIModelName = VertexAIModelName.byQualifiedName(modelParameters.name())
                .orElseThrow(() -> new IllegalArgumentException("Unsupported model: " + modelParameters.name()));

        var builder = VertexAiGeminiChatModel.builder()
                .project(credentials.getProjectId())
                .location(config.configuration().get("location"))
                .modelName(vertexAIModelName.toString())
                .credentials(credentials.createScoped(clientConfig.getVertexAIClient().scope()))
                .logRequests(clientConfig.getLogRequests())
                .logResponses(clientConfig.getLogResponses());

        // Add optional parameters
        Optional.ofNullable(modelParameters.temperature())
                .map(Double::floatValue)
                .ifPresent(builder::temperature);
        Optional.ofNullable(modelParameters.seed())
                .ifPresent(builder::seed);

        // Add OpenTelemetry instrumentation listener
        if (chatModelListener != null) {
            builder.listeners(List.of(chatModelListener));
        }

        return builder.build();
    }
}

