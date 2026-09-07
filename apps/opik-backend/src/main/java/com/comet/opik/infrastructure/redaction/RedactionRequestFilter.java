package com.comet.opik.infrastructure.redaction;

import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.io.IOException;
import java.util.Set;

/**
 * Resolves once per request whether this caller's response must be redacted, and records it where the write can
 * find it again.
 * <p>
 * The priority places it after authentication so the permissions the platform resolved are already on the
 * context. Request filters run lowest-priority-first and the auth filter carries the default
 * {@code Priorities.USER}, so this has to sit above it; sharing that default leaves the order undefined, and
 * running first makes every caller — admins included — look unpermitted. The decision is recorded here rather
 * than at write time because a response can be serialized in more than one pass when it is streamed.
 * <p>
 * It is recorded twice, on purpose. {@code RequestContext} is what the resources read, and it is
 * {@code @RequestScoped} and therefore thread-bound; the Jersey request property is not, so the writer
 * interceptor can read the same answer on whichever thread the response is written from — including the reactor
 * thread that resumes a {@code @Suspended AsyncResponse}.
 */
@jakarta.ws.rs.ext.Provider
@Singleton
@Priority(Priorities.USER + 100)
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class RedactionRequestFilter implements ContainerRequestFilter {

    /**
     * The paths that carry stored content to an authenticated caller.
     * <p>
     * Redaction only means something where there is a caller to decide about, so this tracks what
     * {@code AuthFilter} authenticates rather than the whole versioned API. Covering more than that redacts
     * responses with no identity behind them: {@code /v1/internal/usage/*} is unauthenticated, and its
     * per-user rows would come back masked, collapsing the platform's usage attribution into one bucket.
     * <p>
     * {@code /v1/internal/analytics-queries} is named explicitly because it is authenticated and returns
     * stored trace content from caller-supplied SQL — a private-prefix test alone would miss it. A new
     * authenticated content path has to be added here, which is the same deliberate act as adding it to
     * {@code AuthFilter}.
     */
    private static final Set<String> COVERED_PATHS = Set.of(
            "/v1/private/",
            "/v1/internal/analytics-queries");

    /**
     * Where the decision is left for {@link RedactionWriterInterceptor}.
     * <p>
     * A Jersey request property rather than the request context, because the write does not always happen on the
     * request thread: {@code LocalRunnersResource.nextJob} and the other long-polling endpoints resume a
     * {@code @Suspended AsyncResponse} from a reactor thread, where the {@code @RequestScoped} context cannot be
     * resolved at all. The property lives on the {@code ContainerRequest}, which Jersey hands to the writer
     * interceptor chain as its properties delegate, so it is visible from any thread that writes this response.
     */
    static final String REDACT_RESPONSE_PROPERTY = "com.comet.opik.redaction.redact-response";

    private final @NonNull RedactionService redactionService;
    private final @NonNull Provider<RequestContext> requestContext;

    @Override
    public void filter(ContainerRequestContext context) throws IOException {
        if (!redactionService.isEnabled()) {
            return;
        }
        if (!coversPath(context.getUriInfo().getRequestUri().getPath())) {
            return;
        }

        RequestContext current = requestContext.get();
        boolean redact = redactionService.shouldRedactFor(current.getPermissions());

        current.setRedactResponse(redact);
        context.setProperty(REDACT_RESPONSE_PROPERTY, redact);
    }

    /**
     * Whether redaction covers this path. See {@link #COVERED_PATHS} for why this is not simply the whole
     * versioned API.
     */
    static boolean coversPath(@NonNull String path) {
        return COVERED_PATHS.stream().anyMatch(path::startsWith);
    }
}
