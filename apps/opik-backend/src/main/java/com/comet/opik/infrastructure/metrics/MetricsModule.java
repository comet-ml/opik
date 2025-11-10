package com.comet.opik.infrastructure.metrics;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.matcher.Matchers;
import ru.vyarus.dropwizard.guice.module.support.DropwizardAwareModule;

/**
 * Guice module that registers the {@link MetricsInterceptor} for automatic metrics collection.
 *
 * <p>This module sets up AOP interception for all methods annotated with {@link Metered},
 * enabling automatic collection of OpenTelemetry metrics without modifying business logic.
 *
 * <p>The module is registered in {@code OpikApplication} and should not be manually instantiated.
 */
public class MetricsModule extends DropwizardAwareModule<OpikConfiguration> {

    @Override
    protected void configure() {
        var requestContext = getProvider(RequestContext.class);
        var metricsInterceptor = new MetricsInterceptor(requestContext);

        bindInterceptor(Matchers.any(), Matchers.annotatedWith(Metered.class), metricsInterceptor);
    }
}
