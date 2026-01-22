package com.comet.opik.infrastructure;

import com.comet.opik.domain.evaluators.python.CommonMetricsRegistry;
import com.google.inject.Provides;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;

/**
 * Guice module that provides the CommonMetricsRegistry singleton.
 * The registry is loaded at application startup and parses Python SDK
 * heuristic files to extract metric metadata.
 */
@Slf4j
public class CommonMetricsModule extends DropwizardAwareModule<OpikConfiguration> {

    @Provides
    @Singleton
    public CommonMetricsRegistry getCommonMetricsRegistry() {
        log.info("Initializing CommonMetricsRegistry");
        return new CommonMetricsRegistry();
    }
}
