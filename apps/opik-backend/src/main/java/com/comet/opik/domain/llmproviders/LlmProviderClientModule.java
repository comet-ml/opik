package com.comet.opik.domain.llmproviders;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.google.inject.Provides;
import jakarta.inject.Singleton;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

public class LlmProviderClientModule extends DropwizardAwareModule<OpikConfiguration> {

    @Provides
    @Singleton
    public LlmProviderClientGenerator clientGenerator(
            @NonNull @Config("llmProviderClient") LlmProviderClientConfig config) {
        return new LlmProviderClientGenerator(config);
    }
}
