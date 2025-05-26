package com.comet.opik.infrastructure.servlet;

import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.common.TextFormat;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.io.Writer;

public class PrometheusMetricsServlet extends HttpServlet {

    private final CollectorRegistry registry;

    public PrometheusMetricsServlet(CollectorRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.setContentType(TextFormat.CONTENT_TYPE_004);

        try (Writer writer = resp.getWriter()) {
            TextFormat.write004(writer, registry.metricFamilySamples());
        }
    }
}