package com.comet.opik.infrastructure.metrics;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.core.exc.StreamConstraintsException;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.core.UriInfo;
import org.apache.commons.lang3.StringUtils;

import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.ENDPOINT_KEY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.UNKNOWN;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.WORKSPACE_ID_KEY;
import static com.comet.opik.infrastructure.metrics.ErrorMetricsResolver.WORKSPACE_NAME_KEY;

/**
 * Emits {@code ingestion_size_guard_rejections_total} for requests rejected by a server-side ingestion
 * size guard (OPIK-7333/7334), labelled by {@code component}, {@code guard}, {@code endpoint} and
 * {@code workspace_id}/{@code workspace_name}. These rejections never reach {@link HttpErrorMetrics},
 * so they need their own counter.
 */
@Singleton
public class IngestionSizeGuardMetrics {

    private static final String METRIC_NAMESPACE = "ingestion_size_guard";

    public static final AttributeKey<String> GUARD_KEY = AttributeKey.stringKey("guard");
    public static final AttributeKey<String> COMPONENT_KEY = AttributeKey.stringKey("component");

    public static final String GUARD_REQUEST_SIZE = "request_size";
    public static final String GUARD_DOCUMENT_LENGTH = "document_length";
    public static final String GUARD_STRING_LENGTH = "string_length";
    public static final String GUARD_UNCLASSIFIED = "unclassified"; // unclassified fallback

    public static final String COMPONENT_REQUEST_FILTER = "request_filter";
    public static final String COMPONENT_JSON_PARSER = "json_parser";

    private final LongCounter rejectionCounter;

    public IngestionSizeGuardMetrics() {
        Meter meter = GlobalOpenTelemetry.get().getMeter(METRIC_NAMESPACE);
        this.rejectionCounter = meter
                .counterBuilder("%s_rejections_total".formatted(METRIC_NAMESPACE))
                .setDescription("Ingestion requests rejected by a server-side size guard, by component "
                        + "(request_filter|json_parser), guard (request_size|document_length|string_length), "
                        + "endpoint and workspace.")
                .build();
    }

    public void recordRequestSizeRejection(UriInfo uriInfo, Provider<RequestContext> requestContext) {
        record(COMPONENT_REQUEST_FILTER, GUARD_REQUEST_SIZE, ErrorMetricsResolver.endpoint(uriInfo),
                ErrorMetricsResolver.workspaceId(requestContext),
                ErrorMetricsResolver.workspaceName(requestContext));
    }

    public void recordStreamConstraintRejection(StreamConstraintsException exception, UriInfo uriInfo,
            Provider<RequestContext> requestContext) {
        record(COMPONENT_JSON_PARSER, classifyStreamConstraint(exception), ErrorMetricsResolver.endpoint(uriInfo),
                ErrorMetricsResolver.workspaceId(requestContext),
                ErrorMetricsResolver.workspaceName(requestContext));
    }

    public void record(String component, String guard, String endpoint, String workspaceId, String workspaceName) {
        var resolvedWorkspaceId = StringUtils.defaultIfBlank(workspaceId, UNKNOWN);
        rejectionCounter.add(1, Attributes.builder()
                .put(COMPONENT_KEY, StringUtils.defaultIfBlank(component, UNKNOWN))
                .put(GUARD_KEY, StringUtils.defaultIfBlank(guard, GUARD_UNCLASSIFIED))
                .put(ENDPOINT_KEY, StringUtils.defaultIfBlank(endpoint, UNKNOWN))
                .put(WORKSPACE_ID_KEY, resolvedWorkspaceId)
                .put(WORKSPACE_NAME_KEY, StringUtils.defaultIfBlank(workspaceName, resolvedWorkspaceId))
                .build());
    }

    // Classifies via Jackson's message text (the only signal it exposes): version-dependent, guarded by
    // IngestionSizeGuardMetricsTest; re-check on a Jackson bump. Unrecognized -> unclassified.
    public static String classifyStreamConstraint(StreamConstraintsException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return GUARD_UNCLASSIFIED;
        }
        if (message.contains("getMaxDocumentLength") || message.contains("Document length")) {
            return GUARD_DOCUMENT_LENGTH;
        }
        if (message.contains("getMaxStringLength") || message.contains("String value length")) {
            return GUARD_STRING_LENGTH;
        }
        return GUARD_UNCLASSIFIED;
    }
}
