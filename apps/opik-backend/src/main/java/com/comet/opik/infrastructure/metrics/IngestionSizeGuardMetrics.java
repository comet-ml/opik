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
 * Emits the {@code ingestion_size_guard_rejections_total} counter for requests rejected by the
 * server-side ingestion size guards (OPIK-7333 / OPIK-7334), broken down by the emitting {@code
 * component} (request filter vs JSON parser), which {@code guard} tripped, the matched route
 * ({@code endpoint}) and the {@code workspace_id} / {@code workspace_name}.
 * <p>
 * These rejections are otherwise invisible to {@link HttpErrorMetrics} ({@code opik.errors.count}):
 * the request-size 413 aborts the filter chain with a Response (no throwable), and the
 * document/string-length 4xx is handled by the more-specific {@link
 * com.comet.opik.api.error.JsonProcessingExceptionMapper} rather than the generic mapper that feeds
 * the error counter. The dedicated {@code guard} label also distinguishes an oversized-payload
 * rejection from an ordinary malformed-JSON 400, which a status-only or {@code error_type} view
 * cannot.
 * <p>
 * The request-size guard runs at the request boundary, possibly before authentication resolves the
 * workspace; {@link ErrorMetricsResolver#workspaceId(Provider)} falls back to {@code unknown} in that
 * case rather than failing.
 */
@Singleton
public class IngestionSizeGuardMetrics {

    private static final String METRIC_NAMESPACE = "ingestion_size_guard";

    public static final AttributeKey<String> GUARD_KEY = AttributeKey.stringKey("guard");
    // A stable origin label: one counter serves two call sites, so distinguish them by component
    // rather than by inferring from the guard subtype (metrics-instrumentation skill §1.4).
    public static final AttributeKey<String> COMPONENT_KEY = AttributeKey.stringKey("component");

    /** The {@link com.comet.opik.infrastructure.RequestSizeLimitFilter} 413 (Content-Length over cap). */
    public static final String GUARD_REQUEST_SIZE = "request_size";
    /** The whole decompressed document exceeded {@code maxDocumentLength}. */
    public static final String GUARD_DOCUMENT_LENGTH = "document_length";
    /** A single string value exceeded {@code maxStringLength}. */
    public static final String GUARD_STRING_LENGTH = "string_length";
    /** A stream-read constraint we couldn't classify from its message. */
    public static final String GUARD_STREAM_CONSTRAINT = "stream_constraint";

    /** Rejection emitted from {@link com.comet.opik.infrastructure.RequestSizeLimitFilter} (413 path). */
    public static final String COMPONENT_REQUEST_FILTER = "request_filter";
    /** Rejection emitted from {@link com.comet.opik.api.error.JsonProcessingExceptionMapper} (4xx parse path). */
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

    /**
     * Records a request-size (413) rejection. Called from the request filter, where the workspace may
     * not be resolved yet and resolves to {@code unknown}.
     */
    public void recordRequestSizeRejection(UriInfo uriInfo, Provider<RequestContext> requestContext) {
        record(COMPONENT_REQUEST_FILTER, GUARD_REQUEST_SIZE, ErrorMetricsResolver.endpoint(uriInfo),
                ErrorMetricsResolver.workspaceId(requestContext),
                ErrorMetricsResolver.workspaceName(requestContext));
    }

    /**
     * Records a document- or string-length rejection, classified from the Jackson exception message.
     */
    public void recordStreamConstraintRejection(StreamConstraintsException exception, UriInfo uriInfo,
            Provider<RequestContext> requestContext) {
        record(COMPONENT_JSON_PARSER, classifyStreamConstraint(exception), ErrorMetricsResolver.endpoint(uriInfo),
                ErrorMetricsResolver.workspaceId(requestContext),
                ErrorMetricsResolver.workspaceName(requestContext));
    }

    public void record(String component, String guard, String endpoint, String workspaceId, String workspaceName) {
        // workspace_name falls back to workspace_id when the name is absent (metrics-instrumentation
        // skill §2.3), so the name label is never emptier than the id.
        var resolvedWorkspaceId = StringUtils.defaultIfBlank(workspaceId, UNKNOWN);
        rejectionCounter.add(1, Attributes.builder()
                .put(COMPONENT_KEY, StringUtils.defaultIfBlank(component, UNKNOWN))
                .put(GUARD_KEY, StringUtils.defaultIfBlank(guard, GUARD_STREAM_CONSTRAINT))
                .put(ENDPOINT_KEY, StringUtils.defaultIfBlank(endpoint, UNKNOWN))
                .put(WORKSPACE_ID_KEY, resolvedWorkspaceId)
                .put(WORKSPACE_NAME_KEY, StringUtils.defaultIfBlank(workspaceName, resolvedWorkspaceId))
                .build());
    }

    /**
     * Jackson reports both the document- and string-length overruns as {@link
     * StreamConstraintsException}; the concrete limit is distinguishable only from the message, which
     * references the tripped {@code StreamReadConstraints} accessor. An unrecognized message falls
     * back to {@link #GUARD_STREAM_CONSTRAINT} rather than guessing.
     * <p>
     * NOTE: these substrings are Jackson internals, not a stable contract - a Jackson upgrade that
     * rewords the message would silently collapse both cases into {@code stream_constraint} and
     * degrade the dashboard breakdown. The real-parse tests in {@code IngestionSizeGuardMetricsTest}
     * drive actual Jackson past the caps so that a wording change breaks a test instead of the
     * metric; re-validate these substrings on a Jackson version bump.
     */
    static String classifyStreamConstraint(StreamConstraintsException exception) {
        String message = exception.getMessage();
        if (message == null) {
            return GUARD_STREAM_CONSTRAINT;
        }
        if (message.contains("getMaxDocumentLength") || message.contains("Document length")) {
            return GUARD_DOCUMENT_LENGTH;
        }
        if (message.contains("getMaxStringLength") || message.contains("String value length")) {
            return GUARD_STRING_LENGTH;
        }
        return GUARD_STREAM_CONSTRAINT;
    }
}
