package com.comet.opik.domain;

import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.domain.llm.ChatCompletionService;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ExperimentItemProcessor {

    private final @NonNull ChatCompletionService chatCompletionService;
    private final @NonNull ExperimentMessageRenderer messageRenderer;
    private final @NonNull ExperimentTracePersistence tracePersistence;
    private final @NonNull DatasetItemService datasetItemService;
    private final @NonNull IdGenerator idGenerator;

    public Mono<Void> process(@NonNull ExperimentItemToProcess message) {
        return datasetItemService.get(message.datasetItemId())
                .flatMap(datasetItem -> {
                    var prompt = message.prompt();

                    UUID traceId = idGenerator.generateId();
                    Instant startTime = Instant.now();
                    ChatCompletionResponse llmResponse = null;
                    String errorType = null;
                    String errorMessage = null;

                    var templateContext = messageRenderer.buildTemplateContext(datasetItem);
                    var renderedMessages = messageRenderer.renderMessages(prompt.messages(), templateContext);

                    try {
                        var chatRequest = messageRenderer.buildChatCompletionRequest(prompt, renderedMessages);
                        llmResponse = chatCompletionService.create(chatRequest, message.workspaceId());
                    } catch (Exception e) {
                        log.warn("LLM call failed for experiment '{}', dataset item '{}'",
                                message.experimentId(), datasetItem.id(), e);
                        errorType = e.getClass().getSimpleName();
                        errorMessage = e.getMessage();
                    }

                    Instant endTime = Instant.now();

                    return tracePersistence.persistTraceSpanAndItem(
                            traceId, message.projectName(), prompt, renderedMessages, llmResponse,
                            errorType, errorMessage,
                            startTime, endTime, message.experimentId(), message.datasetId(), message.versionHash(),
                            datasetItem.id());
                });
    }
}
