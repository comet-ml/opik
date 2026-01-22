package com.comet.opik.infrastructure;

import com.comet.opik.domain.evaluators.python.CommonMetricsRegistry;
import com.google.inject.AbstractModule;
import jakarta.inject.Singleton;
import lombok.extern.slf4j.Slf4j;

/**
 * Guice module that provides the CommonMetricsRegistry singleton.
 * The registry fetches metric metadata from the Python backend,
 * which extracts it from the installed opik SDK.
 */
@Slf4j
public class CommonMetricsModule extends AbstractModule {

    @Override
    protected void configure() {
        log.info("Configuring CommonMetricsModule");
        bind(CommonMetricsRegistry.class).in(Singleton.class);
    }
}
