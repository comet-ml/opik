package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.ModelParams;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.LlmProviderService;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.Nullable;

public interface LlmServiceProvider {

    LlmProviderService getService(LlmProviderClientApiConfig config);

    ChatModel getLanguageModel(LlmProviderClientApiConfig config, LlmAsJudgeModelParameters modelParameters);

    default ChatModel getToolCallingLanguageModel(LlmProviderClientApiConfig config,
            LlmAsJudgeModelParameters modelParameters, @Nullable ModelParams.ToolsParam tools) {
        return getLanguageModel(config, modelParameters);
    }
}
