package com.comet.opik.infrastructure.llm;

import dev.langchain4j.model.chat.ChatModel;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters;

public interface LlmProviderClientGenerator<T> {

    T generate(LlmProviderClientApiConfig clientConfig, Object... params);

    ChatModel generateChat(LlmProviderClientApiConfig clientConfig, LlmAsJudgeModelParameters modelParameters);
}
