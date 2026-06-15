package com.comet.opik.infrastructure.metrics;

import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Provider;
import jakarta.ws.rs.core.UriInfo;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.glassfish.jersey.uri.UriTemplate;

import java.util.List;
import java.util.function.Function;

/**
 * Derives the {@code error_type} and {@code endpoint} metric labels.
 * <p>
 * {@code error_type} is the simple class name of the root cause (the chain is unwrapped because the
 * meaningful cause is frequently wrapped, e.g. a ClickHouse read timeout under a generic runtime
 * exception). {@code endpoint} is the matched route template (e.g. {@code /v1/private/traces/{id}})
 * rather than the raw path, so path parameters don't inflate metric cardinality.
 */
@UtilityClass
public class ErrorMetricsResolver {

    public static String errorType(Throwable throwable) {
        if (throwable == null) {
            return ErrorMetrics.UNKNOWN;
        }
        Throwable rootCause = ExceptionUtils.getRootCause(throwable);
        return (rootCause != null ? rootCause : throwable).getClass().getSimpleName();
    }

    public static String endpoint(UriInfo uriInfo) {
        if (!(uriInfo instanceof ExtendedUriInfo extendedUriInfo)) {
            return ErrorMetrics.UNKNOWN;
        }
        List<UriTemplate> templates = extendedUriInfo.getMatchedTemplates();
        if (templates == null || templates.isEmpty()) {
            return ErrorMetrics.UNKNOWN;
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
     * workspace) and any request-scope access issue resolve to {@link ErrorMetrics#UNKNOWN}.
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
            return ErrorMetrics.UNKNOWN;
        }
    }
}
