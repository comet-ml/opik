package com.comet.opik.infrastructure.llm;

import dev.langchain4j.model.chat.ChatLanguageModel;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters;

public interface LlmProviderClientGenerator<T> {

    T generate(String apiKey, Object... params);

    ChatLanguageModel generateChat(String apiKey, LlmAsJudgeModelParameters modelParameters);
}
