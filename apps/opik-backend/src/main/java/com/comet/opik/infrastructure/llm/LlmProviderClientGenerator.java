package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import dev.langchain4j.model.chat.ChatModel;

public interface LlmProviderClientGenerator<T> {

    T generate(LlmProviderClientApiConfig clientConfig, Object... params);

    ChatModel generateChat(LlmProviderClientApiConfig clientConfig, LlmAsJudgeModelParameters modelParameters);
}
