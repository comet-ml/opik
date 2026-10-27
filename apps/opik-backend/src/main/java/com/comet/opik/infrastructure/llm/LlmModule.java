package com.comet.opik.infrastructure.llm;

import com.comet.opik.domain.LlmProviderApiKeyService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.llm.customllm.ProviderBaseUrlGuard;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
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

    /**
     * Downloads media URLs for the providers whose SDKs would otherwise fetch them themselves. The URLs come from any
     * caller's message, or from trace data for online scoring.
     */
    @Provides
    @Singleton
    public RemoteMediaInliner remoteMediaInliner(@NonNull Client client,
            @NonNull @Config("llmProviderClient") LlmProviderClientConfig config) {
        return new RemoteMediaInliner(client, config);
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
