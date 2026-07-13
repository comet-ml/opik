package com.comet.opik.infrastructure.llm.orcarouter;

import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.comet.opik.infrastructure.llm.openai.OpenAIClientGenerator;
import com.comet.opik.utils.JsonUtils;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import jakarta.inject.Named;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.io.IOException;

public class OrcaRouterModule extends AbstractModule {
    @Provides
    @Singleton
    @Named("orcarouterGenerator")
    public OpenAIClientGenerator clientGenerator(
            @NonNull @Config("llmProviderClient") LlmProviderClientConfig config) throws IOException {
        LlmProviderClientConfig customConfig = JsonUtils.readValue(
                JsonUtils.writeValueAsBytes(config), LlmProviderClientConfig.class);
        customConfig.setOpenAiClient(new LlmProviderClientConfig.OpenAiClientConfig(config.getOrcaRouterUrl()));
        return new OpenAIClientGenerator(customConfig);
    }

    @Provides
    @Singleton
    @Named("orcarouter")
    public LlmServiceProvider llmServiceProvider(@NonNull LlmProviderFactory llmProviderFactory,
            @NonNull @Named("orcarouterGenerator") OpenAIClientGenerator clientGenerator) {
        return new OrcaRouterLlmServiceProvider(clientGenerator, llmProviderFactory);
    }
}
