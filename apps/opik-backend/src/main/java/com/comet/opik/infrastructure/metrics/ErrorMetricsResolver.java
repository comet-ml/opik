package com.comet.opik.infrastructure.metrics;

import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Provider;
import jakarta.ws.rs.core.Request;
import jakarta.ws.rs.core.UriInfo;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.glassfish.jersey.uri.UriTemplate;

import java.util.List;
import java.util.function.Function;

/**
 * Shared metric label vocabulary and value resolution for error metrics, used by both the HTTP path
 * ({@link HttpErrorMetrics}) and the async path (redis subscribers).
 * <p>
 * {@code error_type} is the simple class name of the root cause (the chain is unwrapped because the
 * meaningful cause is frequently wrapped, e.g. a ClickHouse read timeout under a generic runtime
 * exception). {@code endpoint} is the matched route template (e.g. {@code /v1/private/traces/{id}})
 * rather than the raw path, so path parameters don't inflate metric cardinality.
 */
@UtilityClass
public class ErrorMetricsResolver {

    public static final String ERROR_TYPE_KEY = "error_type";
    public static final String METHOD_KEY = "method";
    public static final String ENDPOINT_KEY = "endpoint";
    public static final String WORKSPACE_ID_KEY = "workspace_id";
    public static final String WORKSPACE_NAME_KEY = "workspace_name";
    public static final String USER_NAME_KEY = "user_name";
    public static final String UNKNOWN = "unknown";

    public static String errorType(Throwable throwable) {
        if (throwable == null) {
            return UNKNOWN;
        }
        Throwable rootCause = ExceptionUtils.getRootCause(throwable);
        return (rootCause != null ? rootCause : throwable).getClass().getSimpleName();
    }

    public static String method(Provider<Request> request) {
        try {
            return request.get().getMethod();
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
    }

    public static String endpoint(UriInfo uriInfo) {
        if (!(uriInfo instanceof ExtendedUriInfo extendedUriInfo)) {
            return UNKNOWN;
        }
        List<UriTemplate> templates = extendedUriInfo.getMatchedTemplates();
        if (templates == null || templates.isEmpty()) {
            return UNKNOWN;
        }
        // getMatchedTemplates() is ordered from most specific (leaf) to least specific (root); reverse
        // to reconstruct the full route template.
        var builder = new StringBuilder();
        for (int i = templates.size() - 1; i >= 0; i--) {
            builder.append(templates.get(i).getTemplate());
        }
        return builder.toString();
    }

    /**
     * Reads the {@code workspace_id} from the request context. Pre-auth failures (no resolved
     * workspace) and any request-scope access issue resolve to {@link #UNKNOWN}.
     */
    public static String workspaceId(Provider<RequestContext> requestContext) {
        return readContext(requestContext, RequestContext::getWorkspaceId);
    }

    /**
     * Reads the {@code workspace_name} from the request context, with the same fallback semantics as
     * {@link #workspaceId(Provider)}.
     */
    public static String workspaceName(Provider<RequestContext> requestContext) {
        return readContext(requestContext, RequestContext::getWorkspaceName);
    }

    private static String readContext(Provider<RequestContext> requestContext,
            Function<RequestContext, String> getter) {
        try {
            return getter.apply(requestContext.get());
        } catch (RuntimeException e) {
            return UNKNOWN;
        }
    }
}
