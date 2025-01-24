package com.comet.opik.infrastructure.llm;

import com.comet.opik.domain.llm.LlmProviderService;
import dev.langchain4j.model.chat.ChatLanguageModel;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters;

public interface LlmServiceProvider {

    LlmProviderService getService(String apiKey);

    ChatLanguageModel getLanguageModel(String apiKey, LlmAsJudgeModelParameters modelParameters);
}
