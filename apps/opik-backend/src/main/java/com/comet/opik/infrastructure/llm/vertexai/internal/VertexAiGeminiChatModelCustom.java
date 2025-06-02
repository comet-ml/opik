package com.comet.opik.infrastructure.llm.vertexai.internal;

import com.google.cloud.vertexai.api.Content;
import com.google.cloud.vertexai.api.FunctionCall;
import com.google.cloud.vertexai.api.FunctionCallingConfig;
import com.google.cloud.vertexai.api.GenerateContentResponse;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.api.Part;
import com.google.cloud.vertexai.api.Schema;
import com.google.cloud.vertexai.api.Tool;
import com.google.cloud.vertexai.api.ToolConfig;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.cloud.vertexai.generativeai.ResponseHandler;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.internal.ChatRequestValidationUtils;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.request.ResponseFormatType;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.model.vertexai.HarmCategory;
import dev.langchain4j.model.vertexai.SafetyThreshold;
import dev.langchain4j.model.vertexai.SchemaHelper;
import dev.langchain4j.model.vertexai.ToolCallingMode;
import dev.langchain4j.model.vertexai.VertexAiGeminiChatModel;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import static dev.langchain4j.internal.RetryUtils.withRetryMappingExceptions;
import static dev.langchain4j.internal.Utils.isNullOrEmpty;

/**
 * This class is a custom implementation of the VertexAiGeminiChatModel.
 *
 * It generates chat responses using the Vertex AI Gemini model.
 * It extends the VertexAiGeminiChatModel class to provide a way to support structured output.
 *
 * */
@Slf4j
public class VertexAiGeminiChatModelCustom extends VertexAiGeminiChatModel {

    private GenerativeModel generativeModel;
    private GenerationConfig generationConfig;

    public VertexAiGeminiChatModelCustom(String project, String location, String modelName, Float temperature,
            Integer maxOutputTokens, Integer topK, Float topP, Integer seed, Integer maxRetries,
            String responseMimeType, Schema responseSchema, Map<HarmCategory, SafetyThreshold> safetySettings,
            Boolean useGoogleSearch, String vertexSearchDatastore, ToolCallingMode toolCallingMode,
            List<String> allowedFunctionNames, Boolean logRequests, Boolean logResponses,
            List<ChatModelListener> listeners) {
        super(project, location, modelName, temperature, maxOutputTokens, topK, topP, seed, maxRetries,
                responseMimeType, responseSchema, safetySettings, useGoogleSearch, vertexSearchDatastore,
                toolCallingMode, allowedFunctionNames, logRequests, logResponses, listeners);
    }

    public VertexAiGeminiChatModelCustom(GenerativeModel generativeModel, GenerationConfig generationConfig) {
        super(generativeModel, generationConfig);
        this.generativeModel = generativeModel;
        this.generationConfig = generationConfig;
    }

    public VertexAiGeminiChatModelCustom(GenerativeModel generativeModel, GenerationConfig generationConfig,
            Integer maxRetries) {
        super(generativeModel, generationConfig, maxRetries);
    }

    @Override
    public ChatResponse chat(ChatRequest chatRequest) {
        ChatRequestParameters parameters = chatRequest.parameters();
        ChatRequestValidationUtils.validateParameters(parameters);
        ChatRequestValidationUtils.validate(parameters.toolChoice());

        Response<AiMessage> response;
        List<ToolSpecification> toolSpecifications = parameters.toolSpecifications();
        if (isNullOrEmpty(toolSpecifications)) {
            response = generate(chatRequest.messages(), chatRequest.responseFormat());
        } else {
            response = generate(chatRequest.messages(), toolSpecifications, chatRequest.responseFormat());
        }

        return ChatResponse.builder()
                .aiMessage(response.content())
                .metadata(ChatResponseMetadata.builder()
                        .tokenUsage(response.tokenUsage())
                        .finishReason(response.finishReason())
                        .build())
                .build();
    }

    private Response<AiMessage> generate(List<ChatMessage> messages, ResponseFormat responseFormat) {
        return generate(messages, new ArrayList<>(), responseFormat);
    }

    private Response<AiMessage> generate(
            List<ChatMessage> messages, List<ToolSpecification> toolSpecifications, ResponseFormat responseFormat) {
        String modelName = generativeModel.getModelName();

        List<Tool> tools = new ArrayList<>();
        if (toolSpecifications != null && !toolSpecifications.isEmpty()) {
            Tool tool = FunctionCallHelper.convertToolSpecifications(toolSpecifications);
            tools.add(tool);
        }

        // Create a new GenerationConfig if responseFormat is specified
        GenerationConfig generationConfig = this.generationConfig;
        if (responseFormat != null && responseFormat.type() == ResponseFormatType.JSON) {
            GenerationConfig.Builder configBuilder = GenerationConfig.newBuilder(this.generationConfig);
            configBuilder.setResponseMimeType("application/json");

            // If a JSON schema is provided, convert it to Vertex AI Schema
            if (responseFormat.jsonSchema() != null) {
                Schema schema = SchemaHelper.from(responseFormat.jsonSchema().rootElement());
                configBuilder.setResponseSchema(schema);
            }

            generationConfig = configBuilder.build();
        }

        GenerativeModel model = this.generativeModel
                .withGenerationConfig(generationConfig)
                .withTools(tools)
                .withToolConfig(ToolConfig.newBuilder()
                        .setFunctionCallingConfig(
                                FunctionCallingConfig.newBuilder()
                                        .setMode(FunctionCallingConfig.Mode.AUTO)
                                        .build())
                        .build());

        ContentsMapper.InstructionAndContent instructionAndContent = ContentsMapper
                .splitInstructionAndContent(messages);

        if (instructionAndContent.systemInstruction != null) {
            model = model.withSystemInstruction(instructionAndContent.systemInstruction);
        }

        GenerativeModel finalModel = model;

        ChatRequest listenerRequest = ChatRequest.builder()
                .messages(messages)
                .parameters(ChatRequestParameters.builder()
                        .modelName(modelName)
                        .temperature((double) generationConfig.getTemperature())
                        .topP((double) generationConfig.getTopP())
                        .maxOutputTokens(generationConfig.getMaxOutputTokens())
                        .toolSpecifications(toolSpecifications)
                        .responseFormat(responseFormat)
                        .build())
                .build();
        ConcurrentHashMap<Object, Object> listenerAttributes = new ConcurrentHashMap<>();
        ChatModelRequestContext chatModelRequestContext = new ChatModelRequestContext(listenerRequest, provider(),
                listenerAttributes);
        listeners().forEach((listener) -> {
            try {
                listener.onRequest(chatModelRequestContext);
            } catch (Exception e) {
                log.warn("Exception while calling model listener (onRequest)", e);
            }
        });

        GenerateContentResponse response = null;
        try {
            int maxRetries = 2;
            response = withRetryMappingExceptions(
                    () -> finalModel.generateContent(instructionAndContent.contents), maxRetries);
        } catch (Exception e) {
            listeners().forEach(listener -> {
                try {
                    ChatModelErrorContext chatModelErrorContext = new ChatModelErrorContext(e, listenerRequest,
                            provider(), listenerAttributes);
                    listener.onError(chatModelErrorContext);
                } catch (Exception t) {
                    log.warn("Exception while calling model listener (onError)", t);
                }
            });

            throw new RuntimeException(e);
        }

        Content content = ResponseHandler.getContent(response);

        List<FunctionCall> functionCalls = content.getPartsList().stream()
                .filter(Part::hasFunctionCall)
                .map(Part::getFunctionCall)
                .collect(Collectors.toList());

        Response finalResponse = null;
        AiMessage aiMessage;

        if (!functionCalls.isEmpty()) {
            List<ToolExecutionRequest> toolExecutionRequests = FunctionCallHelper.fromFunctionCalls(functionCalls);

            aiMessage = AiMessage.from(toolExecutionRequests);
            finalResponse = Response.from(
                    aiMessage,
                    TokenUsageMapper.map(response.getUsageMetadata()),
                    FinishReasonMapper.map(ResponseHandler.getFinishReason(response)));
        } else {
            aiMessage = AiMessage.from(ResponseHandler.getText(response));
            finalResponse = Response.from(
                    aiMessage,
                    TokenUsageMapper.map(response.getUsageMetadata()),
                    FinishReasonMapper.map(ResponseHandler.getFinishReason(response)));
        }

        ChatResponse listenerResponse = ChatResponse.builder()
                .aiMessage(aiMessage)
                .metadata(ChatResponseMetadata.builder()
                        .modelName(modelName)
                        .tokenUsage(finalResponse.tokenUsage())
                        .finishReason(finalResponse.finishReason())
                        .build())
                .build();
        ChatModelResponseContext chatModelResponseContext = new ChatModelResponseContext(listenerResponse,
                listenerRequest, provider(), listenerAttributes);
        listeners().forEach((listener) -> {
            try {
                listener.onResponse(chatModelResponseContext);
            } catch (Exception e) {
                log.warn("Exception while calling model listener (onResponse)", e);
            }
        });

        return finalResponse;
    }

}
