package com.comet.opik.infrastructure.llm;

import com.comet.opik.domain.llm.LlmProviderService;
import dev.langchain4j.model.chat.ChatModel;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters;

public interface LlmServiceProvider {

    LlmProviderService getService(LlmProviderClientApiConfig config);

    ChatModel getLanguageModel(LlmProviderClientApiConfig config, LlmAsJudgeModelParameters modelParameters);
}
