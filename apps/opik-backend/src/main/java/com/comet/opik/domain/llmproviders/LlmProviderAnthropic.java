package com.comet.opik.domain.llmproviders;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import dev.ai4j.openai4j.chat.AssistantMessage;
import dev.ai4j.openai4j.chat.ChatCompletionChoice;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.ai4j.openai4j.chat.ChatCompletionResponse;
import dev.ai4j.openai4j.chat.Delta;
import dev.ai4j.openai4j.chat.Message;
import dev.ai4j.openai4j.chat.Role;
import dev.ai4j.openai4j.chat.SystemMessage;
import dev.ai4j.openai4j.chat.UserMessage;
import dev.ai4j.openai4j.shared.Usage;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.StreamingResponseHandler;
import dev.langchain4j.model.anthropic.internal.api.AnthropicContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageRequest;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageResponse;
import dev.langchain4j.model.anthropic.internal.api.AnthropicMessage;
import dev.langchain4j.model.anthropic.internal.api.AnthropicMessageContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicRole;
import dev.langchain4j.model.anthropic.internal.api.AnthropicTextContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicToolChoice;
import dev.langchain4j.model.anthropic.internal.client.AnthropicClient;
import dev.langchain4j.model.anthropic.internal.client.AnthropicHttpException;
import dev.langchain4j.model.output.Response;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.comet.opik.domain.ChatCompletionService.ERROR_EMPTY_MESSAGES;
import static com.comet.opik.domain.ChatCompletionService.ERROR_NO_COMPLETION_TOKENS;

@Slf4j
class LlmProviderAnthropic implements LlmProviderService {
    private final LlmProviderClientConfig llmProviderClientConfig;
    private final AnthropicClient anthropicClient;

    public LlmProviderAnthropic(LlmProviderClientConfig llmProviderClientConfig, String apiKey) {
        this.llmProviderClientConfig = llmProviderClientConfig;
        this.anthropicClient = newClient(apiKey);
    }

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        var response = anthropicClient.createMessage(toAnthropicCreateMessageRequest(request));

        return ChatCompletionResponse.builder()
                .id(response.id)
                .model(response.model)
                .choices(response.content.stream().map(content -> toChatCompletionChoice(response, content))
                        .toList())
                .usage(Usage.builder()
                        .promptTokens(response.usage.inputTokens)
                        .completionTokens(response.usage.outputTokens)
                        .totalTokens(response.usage.inputTokens + response.usage.outputTokens)
                        .build())
                .build();
    }

    @Override
    public void generateStream(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage,
            @NonNull Runnable handleClose, @NonNull Consumer<Throwable> handleError) {
        validateRequest(request);
        anthropicClient.createMessage(toAnthropicCreateMessageRequest(request),
                new ChunkedResponseHandler(handleMessage, handleClose, handleError, request.model()));
    }

    @Override
    public void validateRequest(ChatCompletionRequest request) {
        // see https://github.com/anthropics/courses/blob/master/anthropic_api_fundamentals/04_parameters.ipynb
        if (CollectionUtils.isEmpty(request.messages())) {
            throw new BadRequestException(ERROR_EMPTY_MESSAGES);
        }
        if (request.maxCompletionTokens() == null) {
            throw new BadRequestException(ERROR_NO_COMPLETION_TOKENS);
        }
    }

    @Override
    public Optional<LlmProviderError> getLlmProviderError(Throwable runtimeException) {
        if (runtimeException instanceof AnthropicHttpException anthropicHttpException) {
            return Optional.of(LlmProviderError.builder()
                    .code(anthropicHttpException.statusCode())
                    .message(anthropicHttpException.getMessage())
                    .build());
        }

        return Optional.empty();
    }

    private AnthropicCreateMessageRequest toAnthropicCreateMessageRequest(ChatCompletionRequest request) {
        var builder = AnthropicCreateMessageRequest.builder();
        Optional.ofNullable(request.toolChoice())
                .ifPresent(toolChoice -> builder.toolChoice(AnthropicToolChoice.from(
                        request.toolChoice().toString())));
        return builder
                .stream(request.stream())
                .model(request.model())
                .messages(request.messages().stream()
                        .filter(message -> List.of(Role.ASSISTANT, Role.USER).contains(message.role()))
                        .map(this::toMessage).toList())
                .system(request.messages().stream()
                        .filter(message -> message.role() == Role.SYSTEM)
                        .map(this::toSystemMessage).toList())
                .temperature(request.temperature())
                .topP(request.topP())
                .stopSequences(request.stop())
                .maxTokens(request.maxCompletionTokens())
                .build();
    }

    private AnthropicMessage toMessage(Message message) {
        if (message.role() == Role.ASSISTANT) {
            return AnthropicMessage.builder()
                    .role(AnthropicRole.ASSISTANT)
                    .content(List.of(new AnthropicTextContent(((AssistantMessage) message).content())))
                    .build();
        }

        if (message.role() == Role.USER) {
            return AnthropicMessage.builder()
                    .role(AnthropicRole.USER)
                    .content(List.of(toAnthropicMessageContent(((UserMessage) message).content())))
                    .build();
        }

        throw new BadRequestException("unexpected message role: " + message.role());
    }

    private AnthropicTextContent toSystemMessage(Message message) {
        if (message.role() != Role.SYSTEM) {
            throw new BadRequestException("expecting only system role, got: " + message.role());
        }

        return new AnthropicTextContent(((SystemMessage) message).content());
    }

    private AnthropicMessageContent toAnthropicMessageContent(Object rawContent) {
        if (rawContent instanceof String content) {
            return new AnthropicTextContent(content);
        }

        throw new BadRequestException("only text content is supported");
    }

    private ChatCompletionChoice toChatCompletionChoice(
            AnthropicCreateMessageResponse response, AnthropicContent content) {
        return ChatCompletionChoice.builder()
                .message(AssistantMessage.builder()
                        .name(content.name)
                        .content(content.text)
                        .build())
                .finishReason(response.stopReason)
                .build();
    }

    private AnthropicClient newClient(String apiKey) {
        var anthropicClientBuilder = AnthropicClient.builder();
        Optional.ofNullable(llmProviderClientConfig.getAnthropicClient())
                .map(LlmProviderClientConfig.AnthropicClientConfig::url)
                .ifPresent(url -> {
                    if (StringUtils.isNotEmpty(url)) {
                        anthropicClientBuilder.baseUrl(url);
                    }
                });
        Optional.ofNullable(llmProviderClientConfig.getAnthropicClient())
                .map(LlmProviderClientConfig.AnthropicClientConfig::version)
                .ifPresent(version -> {
                    if (StringUtils.isNotBlank(version)) {
                        anthropicClientBuilder.version(version);
                    }
                });
        Optional.ofNullable(llmProviderClientConfig.getLogRequests())
                .ifPresent(anthropicClientBuilder::logRequests);
        Optional.ofNullable(llmProviderClientConfig.getLogResponses())
                .ifPresent(anthropicClientBuilder::logResponses);
        // anthropic client builder only receives one timeout variant
        Optional.ofNullable(llmProviderClientConfig.getCallTimeout())
                .ifPresent(callTimeout -> anthropicClientBuilder.timeout(callTimeout.toJavaDuration()));
        return anthropicClientBuilder
                .apiKey(apiKey)
                .build();
    }

    private record ChunkedResponseHandler(
            Consumer<ChatCompletionResponse> handleMessage,
            Runnable handleClose,
            Consumer<Throwable> handleError,
            String model) implements StreamingResponseHandler<AiMessage> {

        @SneakyThrows
        @Override
        public void onNext(String s) {
            handleMessage.accept(ChatCompletionResponse.builder()
                    .model(model)
                    .choices(List.of(ChatCompletionChoice.builder()
                            .delta(Delta.builder()
                                    .content(s)
                                    .role(Role.ASSISTANT)
                                    .build())
                            .build()))
                    .build());
        }

        @Override
        public void onComplete(Response<AiMessage> response) {
            handleMessage.accept(ChatCompletionResponse.builder()
                    .model(model)
                    .choices(List.of(ChatCompletionChoice.builder()
                            .delta(Delta.builder()
                                    .content("")
                                    .role(Role.ASSISTANT)
                                    .build())
                            .build()))
                    .usage(Usage.builder()
                            .promptTokens(response.tokenUsage().inputTokenCount())
                            .completionTokens(response.tokenUsage().outputTokenCount())
                            .totalTokens(response.tokenUsage().totalTokenCount())
                            .build())
                    .id((String) response.metadata().get("id"))
                    .build());
            handleClose.run();
        }

        @Override
        public void onError(Throwable throwable) {
            handleError.accept(throwable);
        }
    }
}
