package com.comet.opik.infrastructure.llm;

import com.comet.opik.domain.LlmProviderApiKeyService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import jakarta.inject.Singleton;

public class LlmModule extends AbstractModule {

    @Provides
    @Singleton
    public LlmProviderFactory llmProviderFactory(LlmProviderApiKeyService llmProviderApiKeyService) {
        return createInstance(llmProviderApiKeyService);
    }

    public LlmProviderFactory createInstance(LlmProviderApiKeyService llmProviderApiKeyService) {
        return new LlmProviderFactoryImpl(llmProviderApiKeyService);
    }
}
