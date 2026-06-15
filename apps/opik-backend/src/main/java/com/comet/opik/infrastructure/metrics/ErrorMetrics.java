package com.comet.opik.infrastructure.metrics;

import com.comet.opik.infrastructure.auth.RequestContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.UriInfo;
import org.apache.commons.lang3.StringUtils;

/**
 * Emits the {@code opik.errors.count} counter for errors returned by the API, broken down by the
 * cause ({@code error_type}), the matched route ({@code endpoint}) and the {@code workspace_id}.
 * <p>
 * Complements the HTTP-status (symptom) view: the status alone can't distinguish a 500 caused by a
 * ClickHouse timeout from one caused by an NPE — {@code error_type} carries that cause.
 */
@Singleton
public class ErrorMetrics {

    public static final String ERROR_TYPE_KEY = "error_type";
    public static final String ENDPOINT_KEY = "endpoint";
    public static final String WORKSPACE_ID_KEY = "workspace_id";
    public static final String WORKSPACE_NAME_KEY = "workspace_name";
    public static final String UNKNOWN = "unknown";

    private final LongCounter errorCounter;

    public ErrorMetrics() {
        Meter meter = GlobalOpenTelemetry.get().getMeter("opik.errors");
        this.errorCounter = meter
                .counterBuilder("opik.errors.count")
                .setDescription("Count of errors returned by the API, broken down by error type")
                .build();
    }

    public void record(Throwable throwable, UriInfo uriInfo, Provider<RequestContext> requestContext) {
        record(ErrorMetricsResolver.errorType(throwable),
                ErrorMetricsResolver.endpoint(uriInfo),
                ErrorMetricsResolver.workspaceId(requestContext),
                ErrorMetricsResolver.workspaceName(requestContext));
    }

    public void record(String errorType, String endpoint, String workspaceId, String workspaceName) {
        errorCounter.add(1, Attributes.builder()
                .put(ERROR_TYPE_KEY, StringUtils.defaultIfBlank(errorType, UNKNOWN))
                .put(ENDPOINT_KEY, StringUtils.defaultIfBlank(endpoint, UNKNOWN))
                .put(WORKSPACE_ID_KEY, StringUtils.defaultIfBlank(workspaceId, UNKNOWN))
                .put(WORKSPACE_NAME_KEY, StringUtils.defaultIfBlank(workspaceName, UNKNOWN))
                .build());
    }
}
