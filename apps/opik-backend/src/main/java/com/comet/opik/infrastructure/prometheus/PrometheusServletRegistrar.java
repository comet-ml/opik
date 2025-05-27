package com.comet.opik.infrastructure.prometheus;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import io.dropwizard.core.setup.Environment;

@Singleton
public class PrometheusServletRegistrar {

    @Inject
    public PrometheusServletRegistrar(PrometheusMetricsServlet servlet, Environment environment) {
        environment.servlets().addServlet("prometheusMetrics", servlet)
                .addMapping("/metrics");
    }
}
