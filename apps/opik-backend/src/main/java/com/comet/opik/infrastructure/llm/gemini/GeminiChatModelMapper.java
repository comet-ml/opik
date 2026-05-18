package com.comet.opik.infrastructure.llm.gemini;

import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import lombok.NonNull;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.time.Duration;

@Mapper
interface GeminiChatModelMapper {

    GeminiChatModelMapper INSTANCE = Mappers.getMapper(GeminiChatModelMapper.class);

    // Gemma 4 always returns `thought=true` Parts from the Google AI Studio API,
    // regardless of `thinkingConfig.includeThoughts`. With `returnThinking=null`
    // (LangChain4j's default), those parts get concatenated into the response
    // text, surfacing the reasoning trace to end users. Forcing `false` drops
    // them — matching how Gemini 2.5/3 already behaves (Gemini suppresses
    // thought parts API-side unless the client explicitly opts in).
    @Mapping(expression = "java(request.model())", target = "modelName")
    @Mapping(expression = "java(request.maxCompletionTokens())", target = "maxOutputTokens")
    @Mapping(expression = "java(request.stop())", target = "stopSequences")
    @Mapping(expression = "java(request.temperature())", target = "temperature")
    @Mapping(expression = "java(request.topP())", target = "topP")
    @Mapping(expression = "java(Boolean.FALSE)", target = "returnThinking")
    GoogleAiGeminiChatModel toGeminiChatModel(
            @NonNull String apiKey, @NonNull ChatCompletionRequest request, @NonNull Duration timeout, int maxRetries,
            boolean logRequests, boolean logResponses);

    @Mapping(expression = "java(request.model())", target = "modelName")
    @Mapping(expression = "java(request.maxCompletionTokens())", target = "maxOutputTokens")
    @Mapping(expression = "java(request.stop())", target = "stopSequences")
    @Mapping(expression = "java(request.temperature())", target = "temperature")
    @Mapping(expression = "java(request.topP())", target = "topP")
    @Mapping(expression = "java(Boolean.FALSE)", target = "returnThinking")
    GoogleAiGeminiStreamingChatModel toGeminiStreamingChatModel(
            @NonNull String apiKey, @NonNull ChatCompletionRequest request, @NonNull Duration timeout, int maxRetries,
            boolean logRequests, boolean logResponses);

}
