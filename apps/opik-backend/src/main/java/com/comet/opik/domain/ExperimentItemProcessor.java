package com.comet.opik.domain;

import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.domain.ExperimentTracePersistence.PersistenceContext;
import com.comet.opik.domain.llm.ChatCompletionService;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ExperimentItemProcessor {

    private final @NonNull ChatCompletionService chatCompletionService;
    private final @NonNull ExperimentMessageRenderer messageRenderer;
    private final @NonNull ExperimentTracePersistence tracePersistence;
    private final @NonNull DatasetItemService datasetItemService;
    private final @NonNull IdGenerator idGenerator;

    private record LlmCallResult(
            ChatCompletionResponse response,
            String errorType,
            String errorMessage,
            Instant startTime,
            Instant endTime) {
    }

    public Mono<Void> process(@NonNull ExperimentItemToProcess message) {
        return datasetItemService.get(message.datasetItemId())
                .flatMap(datasetItem -> {
                    var prompt = message.prompt();
                    var templateContext = messageRenderer.buildTemplateContext(datasetItem);
                    var renderedMessages = messageRenderer.renderMessages(prompt.messages(), templateContext);

                    return Mono.fromCallable(() -> {
                        Instant startTime = Instant.now();
                        try {
                            var chatRequest = messageRenderer.buildChatCompletionRequest(prompt, renderedMessages);
                            var response = chatCompletionService.create(chatRequest, message.workspaceId());
                            return new LlmCallResult(response, null, null, startTime, Instant.now());
                        } catch (Exception e) {
                            log.warn("LLM call failed for experiment '{}', dataset item '{}'",
                                    message.experimentId(), datasetItem.id(), e);
                            return new LlmCallResult(null, e.getClass().getSimpleName(), e.getMessage(),
                                    startTime, Instant.now());
                        }
                    })
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(result -> tracePersistence.persistTraceSpanAndItem(
                                    PersistenceContext.builder()
                                            .traceId(idGenerator.generateId())
                                            .projectName(message.projectName())
                                            .prompt(prompt)
                                            .renderedMessages(renderedMessages)
                                            .llmResponse(result.response())
                                            .errorType(result.errorType())
                                            .errorMessage(result.errorMessage())
                                            .startTime(result.startTime())
                                            .endTime(result.endTime())
                                            .experimentId(message.experimentId())
                                            .datasetId(message.datasetId())
                                            .versionHash(message.versionHash())
                                            .datasetItemId(datasetItem.id())
                                            .build()));
                });
    }
}
