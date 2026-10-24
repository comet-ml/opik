package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.api.ChunkedResponseHandler;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.GeminiMaxOutputTokens;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageType;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.CompleteToolCall;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.PartialToolCallContext;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.output.FinishReason;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.scheduler.Schedulers;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

@RequiredArgsConstructor
@Slf4j
public class LlmProviderVertexAI implements LlmProviderService {

    private static final String CUT_OFF_BEFORE_ANSWERING = "Vertex AI used up the max output tokens limit%s before "
            + "writing any answer. Thinking tokens count toward this limit, so raise Max output tokens and run again";

    private final @NonNull VertexAIClientGenerator llmProviderClientGenerator;
    private final @NonNull LlmProviderClientApiConfig config;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        try (var client = llmProviderClientGenerator.newVertexAIClient(config, request)) {
            ChatResponse response = client.chat(getChatMessages(request));
            if (isCutOffBeforeAnswering(response)) {
                throw cutOffBeforeAnswering(request);
            }
            return LlmProviderLangChainMapper.INSTANCE.toChatCompletionResponse(request, response);
        }
    }

    @Override
    public void generateStream(@NonNull ChatCompletionRequest request, @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage, @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {

        Schedulers.boundedElastic()
                .schedule(() -> {
                    CloseableVertexAiStreamingChatModel client;
                    try {
                        client = llmProviderClientGenerator.newVertexAIStreamingClient(config, request);
                    } catch (Exception e) {
                        handleError.accept(e);
                        handleClose.run();
                        return;
                    }

                    // Release the client's GAX threads exactly once, once the stream terminates
                    // (onComplete/onError) — never when this task returns, which is before the first token.
                    var closed = new AtomicBoolean(false);
                    Runnable closeOnce = () -> {
                        if (closed.compareAndSet(false, true)) {
                            client.close();
                        }
                    };
                    // The consumer gets exactly one terminal; a handler that throws is logged, not propagated.
                    var terminalReached = new AtomicBoolean(false);
                    Runnable handleCloseAndRelease = () -> {
                        terminalReached.set(true);
                        try {
                            handleClose.run();
                        } catch (Exception e) {
                            log.warn("Vertex AI stream close handler failed", e);
                        } finally {
                            closeOnce.run();
                        }
                    };
                    Consumer<Throwable> handleErrorAndRelease = throwable -> {
                        terminalReached.set(true);
                        try {
                            handleError.accept(throwable);
                        } catch (Exception e) {
                            log.warn("Vertex AI stream error handler failed", e);
                        } finally {
                            closeOnce.run();
                        }
                    };

                    try {
                        List<ChatMessage> chatMessages = getChatMessages(request);
                        client.chat(chatMessages, failingWhenCutOffBeforeAnswering(request,
                                new ChunkedResponseHandler(handleMessage, handleCloseAndRelease, handleErrorAndRelease,
                                        request.model())));
                    } catch (Exception e) {
                        if (terminalReached.compareAndSet(false, true)) {
                            // Synchronous failure before any terminal — deliver the error and close the stream.
                            try {
                                handleError.accept(e);
                            } catch (Exception ex) {
                                log.warn("Vertex AI stream error handler failed", ex);
                            }
                            try {
                                handleClose.run();
                            } catch (Exception ex) {
                                log.warn("Vertex AI stream close handler failed", ex);
                            }
                        } else {
                            log.warn("Vertex AI stream failed after a terminal callback had already run", e);
                        }
                        closeOnce.run();
                    }
                });
    }

    // Gemini can spend the whole cap on thinking and finish with no text. Passed through, that reaches the playground
    // as an empty response it asks the user to retry, and no retry can help: only a higher cap can.
    private static boolean isCutOffBeforeAnswering(ChatResponse response) {
        return response.finishReason() == FinishReason.LENGTH && StringUtils.isBlank(response.aiMessage().text());
    }

    // InvalidRequestException is non-retriable, so the cut-off is not re-run (and re-billed) by the retry policy,
    // and it is classified as a 400 on both the streaming and the non-streaming path.
    private static InvalidRequestException cutOffBeforeAnswering(ChatCompletionRequest request) {
        var limit = GeminiMaxOutputTokens.firstPositive(request).map(" (%s)"::formatted).orElse("");
        return new InvalidRequestException(CUT_OFF_BEFORE_ANSWERING.formatted(limit));
    }

    static StreamingChatResponseHandler failingWhenCutOffBeforeAnswering(ChatCompletionRequest request,
            StreamingChatResponseHandler delegate) {
        return new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partialResponse) {
                delegate.onPartialResponse(partialResponse);
            }

            @Override
            public void onPartialResponse(PartialResponse partialResponse, PartialResponseContext context) {
                delegate.onPartialResponse(partialResponse, context);
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking) {
                delegate.onPartialThinking(partialThinking);
            }

            @Override
            public void onPartialThinking(PartialThinking partialThinking, PartialThinkingContext context) {
                delegate.onPartialThinking(partialThinking, context);
            }

            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall) {
                delegate.onPartialToolCall(partialToolCall);
            }

            @Override
            public void onPartialToolCall(PartialToolCall partialToolCall, PartialToolCallContext context) {
                delegate.onPartialToolCall(partialToolCall, context);
            }

            @Override
            public void onCompleteToolCall(CompleteToolCall completeToolCall) {
                delegate.onCompleteToolCall(completeToolCall);
            }

            @Override
            public void onUnmappedRawEvent(Object rawEvent) {
                delegate.onUnmappedRawEvent(rawEvent);
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                if (isCutOffBeforeAnswering(response)) {
                    delegate.onError(cutOffBeforeAnswering(request));
                    return;
                }
                delegate.onCompleteResponse(response);
            }

            @Override
            public void onError(Throwable error) {
                delegate.onError(error);
            }
        };
    }

    private List<ChatMessage> getChatMessages(ChatCompletionRequest request) {
        List<ChatMessage> chatMessages = LlmProviderLangChainMapper.INSTANCE.mapMessages(request);

        // This is a workaround for the Vertex AI API, which requires at least one user or AI message in the request.
        if (chatMessages.stream().noneMatch(chatMessage -> chatMessage.type() == ChatMessageType.AI
                || chatMessage.type() == ChatMessageType.USER)) {
            var newMessages = new ArrayList<ChatMessage>();
            newMessages.add(AiMessage.from("User message:")); // Add an empty user message to the list as has to have at least one user or ai message
            newMessages.addAll(chatMessages);
            chatMessages = newMessages;
        }

        return chatMessages;
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {

    }

    @Override
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable throwable) {
        return LlmProviderLangChainMapper.INSTANCE.getGeminiErrorObject(throwable, log);
    }
}
