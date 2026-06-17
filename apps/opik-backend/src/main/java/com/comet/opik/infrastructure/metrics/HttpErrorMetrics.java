package com.comet.opik.infrastructure.metrics;

import com.comet.opik.infrastructure.auth.RequestContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.UriInfo;
import org.apache.commons.lang3.StringUtils;

import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.ENDPOINT_KEY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.ERROR_TYPE_KEY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.METHOD_KEY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.UNKNOWN;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.WORKSPACE_ID_KEY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.WORKSPACE_NAME_KEY;

/**
 * Emits the {@code opik.errors.count} counter for errors returned over HTTP, broken down by the cause
 * ({@code error_type}), the request {@code method}, the matched route ({@code endpoint}) and the
 * {@code workspace_id} / {@code workspace_name}.
 * <p>
 * Complements the HTTP-status (symptom) view: the status alone can't distinguish a 500 caused by a
 * ClickHouse timeout from one caused by an NPE — {@code error_type} carries that cause.
 */
@Singleton
public class HttpErrorMetrics {

    private final LongCounter errorCounter;

    public HttpErrorMetrics() {
        Meter meter = GlobalOpenTelemetry.get().getMeter("opik.errors");
        this.errorCounter = meter
                .counterBuilder("opik.errors.count")
                .setDescription("Count of errors returned by the API, broken down by error type")
                .build();
    }

    public void record(Throwable throwable, Provider<Request> request, UriInfo uriInfo,
            Provider<RequestContext> requestContext) {
        record(ErrorMetricsResolver.errorType(throwable),
                ErrorMetricsResolver.method(request),
                ErrorMetricsResolver.endpoint(uriInfo),
                ErrorMetricsResolver.workspaceId(requestContext),
                ErrorMetricsResolver.workspaceName(requestContext));
    }

    public void record(String errorType, String method, String endpoint, String workspaceId, String workspaceName) {
        errorCounter.add(1, Attributes.builder()
                .put(ERROR_TYPE_KEY, StringUtils.defaultIfBlank(errorType, UNKNOWN))
                .put(METHOD_KEY, StringUtils.defaultIfBlank(method, UNKNOWN))
                .put(ENDPOINT_KEY, StringUtils.defaultIfBlank(endpoint, UNKNOWN))
                .put(WORKSPACE_ID_KEY, StringUtils.defaultIfBlank(workspaceId, UNKNOWN))
                .put(WORKSPACE_NAME_KEY, StringUtils.defaultIfBlank(workspaceName, UNKNOWN))
                .build());
    }
}
