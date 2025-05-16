package com.comet.opik.domain.llm;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import dev.langchain4j.model.chat.ChatModel;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters;

public interface LlmProviderFactory {

    String ERROR_MODEL_NOT_SUPPORTED = "model not supported %s";

    void register(LlmProvider llmProvider, LlmServiceProvider service);

    LlmProviderService getService(String workspaceId, String model);

    ChatModel getLanguageModel(String workspaceId, LlmAsJudgeModelParameters modelParameters);

}
