package com.comet.opik.infrastructure.instrumentation;

import com.comet.opik.infrastructure.OpenTelemetryConfig;
import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import jakarta.inject.Singleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

public class OpenTelemetryModule extends AbstractModule {

    @Provides
    @Singleton
    public OpenTelemetry openTelemetry(@Config("openTelemetry") OpenTelemetryConfig config) {

        if (config.isDisabled()) {
            return OpenTelemetry.noop();
        }

        return GlobalOpenTelemetry.get();
    }
}
