package com.comet.opik.infrastructure.prometheus;

import com.google.inject.AbstractModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;
import io.dropwizard.core.setup.Environment;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.dropwizard.DropwizardExports;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class MetricsModule extends AbstractModule {

    private final Environment environment;

    @Override
    protected void configure() {
        bind(Environment.class).toInstance(environment);
        bind(PrometheusServletRegistrar.class).asEagerSingleton();
    }

    @Provides
    @Singleton
    public CollectorRegistry providePrometheusCollectorRegistry() {
        CollectorRegistry registry = CollectorRegistry.defaultRegistry;
        registry.register(new DropwizardExports(environment.metrics()));
        return registry;
    }

    @Provides
    @Singleton
    public PrometheusMetricsServlet provideMetricsServlet(CollectorRegistry registry) {
        return new PrometheusMetricsServlet(registry);
    }
}
