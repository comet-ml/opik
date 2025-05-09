package com.comet.opik.infrastructure.llm;

import dev.langchain4j.model.chat.ChatLanguageModel;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters;

public interface LlmProviderClientGenerator<T> {

    T generate(LlmProviderClientApiConfig clientConfig, Object... params);

    ChatLanguageModel generateChat(LlmProviderClientApiConfig clientConfig, LlmAsJudgeModelParameters modelParameters);
}
