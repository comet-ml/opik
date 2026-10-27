package com.comet.opik.infrastructure.llm;

import com.comet.opik.domain.LlmProviderApiKeyService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.llm.customllm.ProviderBaseUrlGuard;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import jakarta.inject.Singleton;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

public class LlmModule extends AbstractModule {

    @Provides
    @Singleton
    public LlmProviderFactory llmProviderFactory(
            @NonNull LlmProviderApiKeyService llmProviderApiKeyService,
            @NonNull @Config OpikConfiguration configuration,
            @NonNull LlmModelRegistryService registryService,
            @NonNull ProviderBaseUrlGuard providerBaseUrlGuard) {
        return createInstance(llmProviderApiKeyService, configuration, registryService, providerBaseUrlGuard);
    }

    public LlmProviderFactory createInstance(
            LlmProviderApiKeyService llmProviderApiKeyService,
            OpikConfiguration configuration,
            LlmModelRegistryService registryService,
            ProviderBaseUrlGuard providerBaseUrlGuard) {
        return new LlmProviderFactoryImpl(llmProviderApiKeyService, configuration, registryService,
                providerBaseUrlGuard);
    }

}
