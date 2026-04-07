package com.comet.opik.domain;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.domain.llm.ChatCompletionService;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.UUID;

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ExperimentItemProcessor {

    private final @NonNull ChatCompletionService chatCompletionService;
    private final @NonNull ExperimentMessageRenderer messageRenderer;
    private final @NonNull ExperimentTracePersistence tracePersistence;
    private final @NonNull IdGenerator idGenerator;

    public void process(
            @NonNull ExperimentExecutionRequest.PromptVariant prompt,
            @NonNull DatasetItem datasetItem,
            @NonNull UUID experimentId,
            @NonNull UUID datasetId,
            String versionHash,
            @NonNull String projectName,
            @NonNull String workspaceId,
            @NonNull String userName) {

        UUID traceId = idGenerator.generateId();
        Instant startTime = Instant.now();
        ChatCompletionResponse llmResponse = null;
        String errorMessage = null;

        var templateContext = messageRenderer.buildTemplateContext(datasetItem);
        var renderedMessages = messageRenderer.renderMessages(prompt.messages(), templateContext);

        try {
            var chatRequest = messageRenderer.buildChatCompletionRequest(prompt, renderedMessages);
            llmResponse = chatCompletionService.create(chatRequest, workspaceId);
        } catch (Exception e) {
            log.warn("LLM call failed for experiment '{}', dataset item '{}'",
                    experimentId, datasetItem.id(), e);
            errorMessage = e.getMessage();
        }

        Instant endTime = Instant.now();

        tracePersistence.persistTraceSpanAndItem(
                traceId, projectName, prompt, renderedMessages, llmResponse, errorMessage,
                startTime, endTime, experimentId, datasetId, versionHash, datasetItem.id(),
                workspaceId, userName);
    }
}
