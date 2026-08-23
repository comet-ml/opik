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
 * Resolves once per request whether this caller's response must be redacted, and records it on the request
 * context for the writer interceptor to act on.
 * <p>
 * The priority places it after authentication so the permissions the platform resolved are already on the
 * context. Request filters run lowest-priority-first and the auth filter carries the default
 * {@code Priorities.USER}, so this has to sit above it; sharing that default leaves the order undefined, and
 * running first makes every caller — admins included — look unpermitted. The decision is recorded here rather
 * than at write time because a response can be serialized in more than one pass when it is streamed.
 */
@jakarta.ws.rs.ext.Provider
@Singleton
@Priority(Priorities.USER + 100)
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class RedactionRequestFilter implements ContainerRequestFilter {

    private static final String VERSIONED_API = "/v1/";

    /**
     * Paths under the versioned API that must be written as stored. Kept to what genuinely cannot carry
     * caller content: a redirect has no body worth rewriting, and resolving a decision for it would add a
     * permission lookup to a hot path for nothing.
     */
    private static final Set<String> EXEMPT_PATHS = Set.of("/v1/session/redirect");

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
        current.setRedactResponse(redactionService.shouldRedactFor(current.getPermissions()));
    }

    /**
     * Whether redaction covers this path.
     * <p>
     * Phrased as "the whole versioned API, less a named exemption list" rather than "only {@code /v1/private}".
     * The narrower form silently missed {@code /v1/internal/analytics-queries}, which returns stored trace
     * content from caller-supplied SQL, and would have missed every future route outside the private prefix the
     * same way — a route is only as protected as somebody's memory of adding it. Anything outside the versioned
     * API is left alone deliberately: the OAuth endpoints return tokens, and a rule written to catch a bearer
     * token or a JWT would happily destroy one.
     */
    static boolean coversPath(@NonNull String path) {
        return path.contains(VERSIONED_API) && EXEMPT_PATHS.stream().noneMatch(path::contains);
    }
}
