package com.comet.opik.domain.llm.langchain4j;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.Content;
import dev.langchain4j.data.message.ImageContent;
import dev.langchain4j.data.message.TextContent;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.VideoContent;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiChatRequestParameters;
import dev.langchain4j.model.openai.OpenAiChatResponseMetadata;
import dev.langchain4j.model.openai.internal.OpenAiClient;
import dev.langchain4j.model.openai.internal.ParsedAndRawResponse;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.chat.Message;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.stream.Collectors;

import static dev.langchain4j.internal.RetryUtils.withRetryMappingExceptions;
import static dev.langchain4j.model.openai.internal.OpenAiUtils.aiMessageFrom;
import static dev.langchain4j.model.openai.internal.OpenAiUtils.finishReasonFrom;
import static dev.langchain4j.model.openai.internal.OpenAiUtils.toOpenAiMessage;
import static dev.langchain4j.model.openai.internal.OpenAiUtils.tokenUsageFrom;
import static dev.langchain4j.model.openai.internal.OpenAiUtils.validate;

/**
 * Custom OpenAI chat model that supports video content through OpikUserMessage.
 * This extends OpenAiChatModel to override the doChat() method where we'll intercept
 * and convert VideoContent to OpikUserMessage format before serialization.
 *
 * This is a hack that we can remove once they add support for video content in the OpenAI API.
 */
@Slf4j
public class OpikOpenAiChatModel extends OpenAiChatModel {

    // Hardcoded defaults for fields we need (matching OpenAiChatModel defaults)
    private final OpenAiClient client;
    private final Integer maxRetries = 2;
    private final Boolean strictTools = false;
    private final Boolean strictJsonSchema = false;
    private final Boolean returnThinking = false;

    // Constructor that takes the parent's builder
    public OpikOpenAiChatModel(OpenAiChatModel.OpenAiChatModelBuilder builder) {
        super(builder);
        // Extract client via reflection since it's private in parent
        try {
            var clientField = OpenAiChatModel.class.getDeclaredField("client");
            clientField.setAccessible(true);
            this.client = (OpenAiClient) clientField.get(this);
        } catch (Exception e) {
            log.error("Failed to extract client from OpenAiChatModel", e);
            throw new RuntimeException("Failed to initialize OpikOpenAiChatModel", e);
        }
    }

    // Provide our own builder method that returns our custom builder
    public static OpenAiChatModel.OpenAiChatModelBuilder builder() {
        return new OpikBuilder();
    }

    // Custom builder that creates OpikOpenAiChatModel instances instead of OpenAiChatModel
    private static class OpikBuilder extends OpenAiChatModel.OpenAiChatModelBuilder {
        @Override
        public OpikOpenAiChatModel build() {
            return new OpikOpenAiChatModel(this);
        }
    }

    @Override
    public ChatResponse doChat(ChatRequest chatRequest) {

        OpenAiChatRequestParameters parameters = (OpenAiChatRequestParameters) chatRequest.parameters();
        validate(parameters);

        // Convert messages: if any UserMessage has VideoContent, convert to OpikUserMessage
        List<Message> messages = toOpikMessages(chatRequest.messages());

        // Build the request with our converted messages
        ChatCompletionRequest openAiRequest = ChatCompletionRequest.builder()
                .messages(messages)
                // Copy all parameters from toOpenAiChatRequest
                .model(parameters.modelName())
                .temperature(parameters.temperature())
                .topP(parameters.topP())
                .frequencyPenalty(parameters.frequencyPenalty())
                .presencePenalty(parameters.presencePenalty())
                .maxTokens(parameters.maxOutputTokens())
                .stop(parameters.stopSequences())
                .maxCompletionTokens(parameters.maxCompletionTokens())
                .logitBias(parameters.logitBias())
                .parallelToolCalls(parameters.parallelToolCalls())
                .seed(parameters.seed())
                .user(parameters.user())
                .store(parameters.store())
                .metadata(parameters.metadata())
                .serviceTier(parameters.serviceTier())
                .reasoningEffort(parameters.reasoningEffort())
                .customParameters(parameters.customParameters())
                .build();

        ParsedAndRawResponse<ChatCompletionResponse> parsedAndRawResponse = withRetryMappingExceptions(
                () -> client.chatCompletion(openAiRequest).executeRaw(), maxRetries);

        ChatCompletionResponse openAiResponse = parsedAndRawResponse.parsedResponse();

        OpenAiChatResponseMetadata responseMetadata = OpenAiChatResponseMetadata.builder()
                .id(openAiResponse.id())
                .modelName(openAiResponse.model())
                .tokenUsage(tokenUsageFrom(openAiResponse.usage()))
                .finishReason(finishReasonFrom(openAiResponse.choices().get(0).finishReason()))
                .created(openAiResponse.created())
                .serviceTier(openAiResponse.serviceTier())
                .systemFingerprint(openAiResponse.systemFingerprint())
                .rawHttpResponse(parsedAndRawResponse.rawHttpResponse())
                .build();

        return ChatResponse.builder()
                .aiMessage(aiMessageFrom(openAiResponse, returnThinking))
                .metadata(responseMetadata)
                .build();
    }

    /**
     * Convert public API messages to OpenAI internal messages, handling VideoContent.
     * This is our adaptation of OpenAiUtils.toOpenAiMessages() with video support.
     */
    private List<Message> toOpikMessages(List<ChatMessage> messages) {
        return messages.stream()
                .map(this::toOpikMessage)
                .collect(Collectors.toList());
    }

    /**
     * Convert a single message, handling VideoContent in UserMessages.
     * This is our adaptation of OpenAiUtils.toOpenAiMessage() with video support.
     */
    private Message toOpikMessage(ChatMessage message) {
        // For non-UserMessage, use the standard conversion
        if (!(message instanceof UserMessage)) {
            return toOpenAiMessage(message);
        }

        UserMessage userMessage = (UserMessage) message;

        // Simple text-only message
        if (userMessage.hasSingleText()) {
            return dev.langchain4j.model.openai.internal.chat.UserMessage.builder()
                    .content(userMessage.singleText())
                    .name(userMessage.name())
                    .build();
        }

        // Multi-content message - check if it has video
        boolean hasVideo = userMessage.contents().stream()
                .anyMatch(content -> content instanceof VideoContent);

        if (!hasVideo) {
            // No video, use standard conversion
            return toOpenAiMessage(message);
        }

        // Has video - convert to OpikUserMessage
        OpikUserMessage.Builder builder = OpikUserMessage.builder();

        for (Content content : userMessage.contents()) {
            if (content instanceof TextContent textContent) {
                builder.addText(textContent.text());
            } else if (content instanceof ImageContent imageContent) {
                builder.addImageUrl(imageContent.image().url().toString());
            } else if (content instanceof VideoContent videoContent) {
                builder.addVideoUrl(videoContent.video().url().toString());
            }
            // Other content types (audio, pdf) are not supported yet in OpikUserMessage
        }

        if (userMessage.name() != null) {
            builder.name(userMessage.name());
        }

        return builder.build();
    }
}