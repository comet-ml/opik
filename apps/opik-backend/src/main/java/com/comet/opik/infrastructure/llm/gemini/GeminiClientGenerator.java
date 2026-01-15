package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.langchain4j.OpikGeminiChatModel;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import com.google.common.base.Preconditions;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static dev.langchain4j.model.googleai.GoogleAiGeminiChatModel.GoogleAiGeminiChatModelBuilder;

@RequiredArgsConstructor
public class GeminiClientGenerator implements LlmProviderClientGenerator<GoogleAiGeminiChatModel> {

    private static final int MAX_RETRIES = 1;
    private final @NonNull LlmProviderClientConfig llmProviderClientConfig;
    private final @NonNull ChatModelListener chatModelListener;

    public GoogleAiGeminiChatModel newGeminiClient(@NonNull String apiKey, @NonNull ChatCompletionRequest request) {
        var builder = GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(request.model())
                .timeout(llmProviderClientConfig.getCallTimeout().toJavaDuration())
                .maxRetries(MAX_RETRIES)
                .logRequests(llmProviderClientConfig.getLogRequests())
                .logResponses(llmProviderClientConfig.getLogResponses());

        // Add optional parameters from request
        Optional.ofNullable(request.temperature()).ifPresent(builder::temperature);
        Optional.ofNullable(request.topP()).ifPresent(builder::topP);
        Optional.ofNullable(request.maxCompletionTokens()).ifPresent(builder::maxOutputTokens);
        Optional.ofNullable(request.stop()).ifPresent(builder::stopSequences);

        // Add OpenTelemetry instrumentation listener for Dataset Expansion and Playground non-streaming
        if (chatModelListener != null) {
            builder.listeners(List.of(chatModelListener));
        }

        return builder.build();
    }

    public GoogleAiGeminiStreamingChatModel newGeminiStreamingClient(
            @NonNull String apiKey, @NonNull ChatCompletionRequest request) {
        var builder = GoogleAiGeminiStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(request.model())
                .timeout(llmProviderClientConfig.getCallTimeout().toJavaDuration())
                .logRequests(llmProviderClientConfig.getLogRequests())
                .logResponses(llmProviderClientConfig.getLogResponses());

        // Add optional parameters from request
        Optional.ofNullable(request.temperature()).ifPresent(builder::temperature);
        Optional.ofNullable(request.topP()).ifPresent(builder::topP);
        Optional.ofNullable(request.maxCompletionTokens()).ifPresent(builder::maxOutputTokens);
        Optional.ofNullable(request.stop()).ifPresent(builder::stopSequences);

        // Add OpenTelemetry instrumentation listener for Playground streaming
        if (chatModelListener != null) {
            builder.listeners(List.of(chatModelListener));
        }

        return builder.build();
    }

    @Override
    public GoogleAiGeminiChatModel generate(LlmProviderClientApiConfig config, Object... params) {
        Preconditions.checkArgument(params.length >= 1, "Expected at least 1 parameter, got " + params.length);
        ChatCompletionRequest request = (ChatCompletionRequest) Objects.requireNonNull(params[0],
                "ChatCompletionRequest is required");
        return newGeminiClient(config.apiKey(), request);
    }

    @Override
    public ChatModel generateChat(LlmProviderClientApiConfig config,
            LlmAsJudgeModelParameters modelParameters) {
        GoogleAiGeminiChatModelBuilder modelBuilder = GoogleAiGeminiChatModel.builder()
                .modelName(modelParameters.name())
                .apiKey(config.apiKey())
                .logRequests(llmProviderClientConfig.getLogRequests())
                .logResponses(llmProviderClientConfig.getLogResponses());

        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> modelBuilder.timeout(connectTimeout.toJavaDuration()));

        Optional.ofNullable(modelParameters.temperature()).ifPresent(modelBuilder::temperature);
        Optional.ofNullable(modelParameters.seed()).ifPresent(modelBuilder::seed);

        // Add OpenTelemetry instrumentation listener
        if (chatModelListener != null) {
            modelBuilder.listeners(List.of(chatModelListener));
        }

        GoogleAiGeminiChatModel geminiModel = modelBuilder.build();

        // Wrap in OpikGeminiChatModel to convert VideoContent -> ImageContent
        return new OpikGeminiChatModel(geminiModel);
    }
}
