package com.comet.opik.domain.llm;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.utils.ChunkedOutputHandlers;
import dev.langchain4j.internal.RetryUtils;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.Optional;
import java.util.function.Consumer;

import static jakarta.ws.rs.core.Response.Status.Family.familyOf;

@Singleton
@Slf4j
public class ChatCompletionService {
    public static final String UNEXPECTED_ERROR_CALLING_LLM_PROVIDER = "Unexpected error calling LLM provider";
    public static final String ERROR_EMPTY_MESSAGES = "messages cannot be empty";
    public static final String ERROR_NO_COMPLETION_TOKENS = "maxCompletionTokens cannot be null";

    private final LlmProviderClientConfig llmProviderClientConfig;
    private final LlmProviderFactory llmProviderFactory;
    private final RetryUtils.RetryPolicy retryPolicy;

    @Inject
    public ChatCompletionService(
            @NonNull @Config LlmProviderClientConfig llmProviderClientConfig,
            @NonNull LlmProviderFactory llmProviderFactory) {
        this.llmProviderClientConfig = llmProviderClientConfig;
        this.llmProviderFactory = llmProviderFactory;
        this.retryPolicy = newRetryPolicy();
    }

    public ChatCompletionResponse create(@NonNull ChatCompletionRequest rawRequest, @NonNull String workspaceId) {
        // must be final or effectively final for lambda
        var request = MessageContentNormalizer.normalizeRequest(rawRequest);

        var llmProviderClient = llmProviderFactory.getService(workspaceId, request.model());
        llmProviderClient.validateRequest(request);

        ChatCompletionResponse chatCompletionResponse;
        try {
            log.info("Creating chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
            chatCompletionResponse = retryPolicy.withRetry(() -> llmProviderClient.generate(request, workspaceId));
        } catch (RuntimeException runtimeException) {
            Optional<ErrorMessage> providerError = llmProviderClient.getLlmProviderError(runtimeException);

            providerError
                    .ifPresent(llmProviderError -> failHandlingLLMProviderError(runtimeException, llmProviderError));

            log.error(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, runtimeException);
            throw new InternalServerErrorException(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);
        }

        log.info("Created chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());
        return chatCompletionResponse;
    }

    public void createAndStreamResponse(
            @NonNull ChatCompletionRequest rawRequest,
            @NonNull String workspaceId,
            @NonNull ChunkedOutputHandlers handlers) {
        var request = MessageContentNormalizer.normalizeRequest(rawRequest);

        log.info("Creating and streaming chat completions, workspaceId '{}', model '{}'", workspaceId, request.model());

        var llmProviderClient = llmProviderFactory.getService(workspaceId, request.model());

        llmProviderClient.generateStream(
                request,
                workspaceId,
                handlers::handleMessage,
                handlers::handleClose,
                getErrorHandler(handlers, llmProviderClient));

        log.info("Created and streaming chat completions, workspaceId '{}', model '{}'", workspaceId,
                request.model());
    }

    public ChatResponse scoreTrace(@NonNull ChatRequest chatRequest,
            @NonNull LlmAsJudgeModelParameters modelParameters,
            @NonNull String workspaceId) {
        // Check if we have multimodal content (images/videos) in the request
        boolean hasMultimodalContent = chatRequest.messages().stream()
                .anyMatch(msg -> msg instanceof dev.langchain4j.data.message.UserMessage userMsg
                        && userMsg.contents().stream()
                                .anyMatch(content -> content instanceof dev.langchain4j.data.message.ImageContent
                                        || content instanceof dev.langchain4j.data.message.VideoContent));

        // For custom LLMs, always use direct ChatCompletionRequest API
        // This is required because OpenAiChatModel doesn't support VideoContent serialization.
        // As a bonus, this path also handles legacy vLLM versions (<0.6.0) via automatic
        // SSE fallback in CustomLlmProvider.
        var llmProvider = llmProviderFactory.getLlmProvider(modelParameters.name());
        if (llmProvider == com.comet.opik.api.LlmProvider.CUSTOM_LLM) {
            log.info(
                    "Using direct ChatCompletionRequest API for custom LLM '{}' (hasMultimodal={}), workspaceId '{}'",
                    modelParameters.name(), hasMultimodalContent, workspaceId);
            return scoreTraceViaDirectAPI(chatRequest, modelParameters, workspaceId);
        }

        // Standard path for non-multimodal or non-custom-LLM providers
        var languageModelClient = llmProviderFactory.getLanguageModel(workspaceId, modelParameters);

        ChatResponse chatResponse;
        try {
            log.info("Initiating chat with model '{}' expecting structured response, workspaceId '{}'",
                    modelParameters.name(), workspaceId);
            chatResponse = retryPolicy
                    .withRetry(() -> languageModelClient.chat(chatRequest));
            log.info("Completed chat with model '{}' expecting structured response, workspaceId '{}'",
                    modelParameters.name(), workspaceId);
            return chatResponse;
        } catch (RuntimeException runtimeException) {
            LlmProviderService provider = llmProviderFactory.getService(workspaceId, modelParameters.name());

            Optional<ErrorMessage> providerError = provider.getLlmProviderError(runtimeException);

            providerError
                    .ifPresent(llmProviderError -> failHandlingLLMProviderError(runtimeException, llmProviderError));

            log.error(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, runtimeException);
            throw new InternalServerErrorException(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, runtimeException);
        }
    }

    /**
     * Score trace using direct ChatCompletionRequest API (bypassing LangChain4j ChatModel).
     * This is needed for custom LLM providers with multimodal content (images/videos).
     *
     * Strategy:
     * 1. Flatten ChatRequest messages to strings (with <<<video>>> tags)
     * 2. Build ChatCompletionRequest with those strings
     * 3. Apply MessageContentNormalizer.normalizeRequest() to expand tags to structured content (same as Playground)
     * 4. Generate response via direct provider.generate() API
     * 5. Convert ChatCompletionResponse back to ChatResponse
     *
     * This preserves structured output instructions (from InstructionStrategy) in the text content.
     */
    private ChatResponse scoreTraceViaDirectAPI(@NonNull ChatRequest chatRequest,
            @NonNull LlmAsJudgeModelParameters modelParameters,
            @NonNull String workspaceId) {
        try {
            // Step 1: Flatten ChatRequest messages to strings with <<<video>>> tags
            var stringMessages = new java.util.ArrayList<dev.langchain4j.model.openai.internal.chat.Message>();
            for (var message : chatRequest.messages()) {
                if (message instanceof dev.langchain4j.data.message.UserMessage userMessage) {
                    // Flatten content back to string with video tags
                    String flattenedContent = MessageContentNormalizer.flattenContent(userMessage.contents());
                    stringMessages.add(dev.langchain4j.model.openai.internal.chat.UserMessage.builder()
                            .content(flattenedContent)
                            .build());
                } else if (message instanceof dev.langchain4j.data.message.AiMessage aiMessage) {
                    stringMessages.add(dev.langchain4j.model.openai.internal.chat.AssistantMessage.builder()
                            .content(aiMessage.text())
                            .build());
                } else if (message instanceof dev.langchain4j.data.message.SystemMessage systemMessage) {
                    stringMessages.add(dev.langchain4j.model.openai.internal.chat.SystemMessage.builder()
                            .content(systemMessage.text())
                            .build());
                }
            }

            // Step 2: Build ChatCompletionRequest
            var rawChatCompletionRequest = dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest.builder()
                    .model(modelParameters.name())
                    .messages(stringMessages)
                    .stream(false) // Explicitly disable streaming to get plain JSON response
                    .streamOptions(null) // Explicitly set streamOptions to null to prevent any streaming
                    .build();

            log.info("Built ChatCompletionRequest with stream='{}', streamOptions='{}' for model '{}'",
                    rawChatCompletionRequest.stream(), rawChatCompletionRequest.streamOptions(),
                    rawChatCompletionRequest.model());

            // Step 3: Normalize the request - this expands <<<video>>> tags to structured content (same as Playground)
            var chatCompletionRequest = MessageContentNormalizer.normalizeRequest(rawChatCompletionRequest);

            log.info("After normalization, ChatCompletionRequest stream='{}', streamOptions='{}' for model '{}'",
                    chatCompletionRequest.stream(), chatCompletionRequest.streamOptions(),
                    chatCompletionRequest.model());

            // Step 4: Generate response via provider
            var provider = llmProviderFactory.getService(workspaceId, modelParameters.name());
            var chatCompletionResponse = retryPolicy
                    .withRetry(() -> provider.generate(chatCompletionRequest, workspaceId));

            // Step 5: Convert response back to ChatResponse
            var aiMessageText = chatCompletionResponse.choices().isEmpty()
                    ? ""
                    : Optional.ofNullable(chatCompletionResponse.choices().get(0).message())
                            .map(dev.langchain4j.model.openai.internal.chat.AssistantMessage::content)
                            .orElse("");

            var aiMessage = dev.langchain4j.data.message.AiMessage.from(aiMessageText);

            var inputTokens = Optional.ofNullable(chatCompletionResponse.usage())
                    .map(dev.langchain4j.model.openai.internal.shared.Usage::promptTokens)
                    .orElse(0);
            var outputTokens = Optional.ofNullable(chatCompletionResponse.usage())
                    .map(dev.langchain4j.model.openai.internal.shared.Usage::completionTokens)
                    .orElse(0);
            var tokenUsage = new dev.langchain4j.model.output.TokenUsage(inputTokens, outputTokens);

            var finishReason = chatCompletionResponse.choices().isEmpty()
                    ? null
                    : dev.langchain4j.model.output.FinishReason.valueOf(
                            chatCompletionResponse.choices().get(0).finishReason().toUpperCase());

            return dev.langchain4j.model.chat.response.ChatResponse.builder()
                    .aiMessage(aiMessage)
                    .tokenUsage(tokenUsage)
                    .finishReason(finishReason)
                    .build();

        } catch (RuntimeException runtimeException) {
            LlmProviderService provider = llmProviderFactory.getService(workspaceId, modelParameters.name());

            Optional<ErrorMessage> providerError = provider.getLlmProviderError(runtimeException);

            providerError
                    .ifPresent(llmProviderError -> failHandlingLLMProviderError(runtimeException, llmProviderError));

            log.error(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, runtimeException);
            throw new InternalServerErrorException(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, runtimeException);
        }
    }

    private void failHandlingLLMProviderError(RuntimeException runtimeException, ErrorMessage llmProviderError) {
        log.warn(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, runtimeException);

        if (familyOf(llmProviderError.getCode()) == Response.Status.Family.CLIENT_ERROR) {
            throw new ClientErrorException(llmProviderError.getMessage(), llmProviderError.getCode());
        }

        throw new ServerErrorException(llmProviderError.getMessage(), llmProviderError.getCode());
    }

    private RetryUtils.RetryPolicy newRetryPolicy() {
        var retryPolicyBuilder = RetryUtils.retryPolicyBuilder();
        Optional.ofNullable(llmProviderClientConfig.getMaxAttempts()).ifPresent(retryPolicyBuilder::maxRetries);
        Optional.ofNullable(llmProviderClientConfig.getJitterScale()).ifPresent(retryPolicyBuilder::jitterScale);
        Optional.ofNullable(llmProviderClientConfig.getBackoffExp()).ifPresent(retryPolicyBuilder::backoffExp);
        return retryPolicyBuilder.delayMillis(llmProviderClientConfig.getDelayMillis()).build();
    }

    private Consumer<Throwable> getErrorHandler(ChunkedOutputHandlers handlers, LlmProviderService llmProviderClient) {
        return throwable -> {
            Optional<ErrorMessage> providerError = llmProviderClient.getLlmProviderError(throwable);

            if (providerError.isPresent()) {
                log.warn(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, throwable);
                handlers.handleError(providerError.get());
            } else {

                if (throwable instanceof BadRequestException userMessage) {
                    log.warn(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, userMessage);
                    handlers.handleError(
                            new ErrorMessage(userMessage.getResponse().getStatus(), userMessage.getMessage()));
                    return;
                }

                log.error(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER, throwable);
                var errorMessage = new ErrorMessage(ChatCompletionService.UNEXPECTED_ERROR_CALLING_LLM_PROVIDER);
                handlers.handleError(errorMessage);
            }
        };
    }
}
