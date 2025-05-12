package com.comet.opik.infrastructure.llm.gemini;

import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import lombok.NonNull;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.factory.Mappers;

import java.time.Duration;

@Mapper
interface GeminiChatModelMapper {

    GeminiChatModelMapper INSTANCE = Mappers.getMapper(GeminiChatModelMapper.class);

    @Mapping(expression = "java(request.model())", target = "modelName")
    @Mapping(expression = "java(request.maxCompletionTokens())", target = "maxOutputTokens")
    @Mapping(expression = "java(request.stop())", target = "stopSequences")
    @Mapping(expression = "java(request.temperature())", target = "temperature")
    @Mapping(expression = "java(request.topP())", target = "topP")
    GoogleAiGeminiChatModel toGeminiChatModel(
            @NonNull String apiKey, @NonNull ChatCompletionRequest request, @NonNull Duration timeout, int maxRetries);

    @Mapping(expression = "java(request.model())", target = "modelName")
    @Mapping(expression = "java(request.maxCompletionTokens())", target = "maxOutputTokens")
    @Mapping(expression = "java(request.stop())", target = "stopSequences")
    @Mapping(expression = "java(request.temperature())", target = "temperature")
    @Mapping(expression = "java(request.topP())", target = "topP")
    GoogleAiGeminiStreamingChatModel toGeminiStreamingChatModel(
            @NonNull String apiKey, @NonNull ChatCompletionRequest request, @NonNull Duration timeout, int maxRetries);

}
