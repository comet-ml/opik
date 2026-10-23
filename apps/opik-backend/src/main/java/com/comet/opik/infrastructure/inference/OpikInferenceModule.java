package com.comet.opik.infrastructure.inference;

import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.OpikInferenceConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import lombok.NonNull;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

public class OpikInferenceModule extends AbstractModule {

    @Provides
    @Singleton
    public OpikInferenceService opikInferenceService(@NonNull RetriableHttpClient client,
            @NonNull @Config("opikInference") OpikInferenceConfig config, @NonNull FeatureFlags featureFlags) {
        return new OpikInferenceService(client, config, featureFlags);
    }
}
