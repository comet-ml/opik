package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import dev.langchain4j.model.chat.ChatModel;

public interface LlmProviderClientGenerator<T> {

    /**
     * Retries a judge's chat model makes on its own: none. {@code ChatCompletionService.scoreTrace} already retries
     * every judge call and the scoring stream redelivers it, so a client's own retries would only multiply the
     * attempts, holding a scoring slot about three times as long while a provider hangs.
     */
    int JUDGE_MAX_RETRIES = 0;

    T generate(LlmProviderClientApiConfig clientConfig, Object... params);

    ChatModel generateChat(LlmProviderClientApiConfig clientConfig, LlmAsJudgeModelParameters modelParameters);
}
