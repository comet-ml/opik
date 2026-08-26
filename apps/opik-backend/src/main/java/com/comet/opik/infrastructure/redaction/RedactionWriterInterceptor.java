package com.comet.opik.infrastructure.redaction;

import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.ext.WriterInterceptor;
import jakarta.ws.rs.ext.WriterInterceptorContext;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.io.IOException;

/**
 * Puts the rule set in force while one response body is written, and takes it out again afterwards.
 * <p>
 * This is the narrowest point that still catches everything: it wraps the serialization itself, so an
 * endpoint that does not exist yet is covered without anyone touching it. The rules are cleared in a finally
 * block because these threads are pooled — a set left behind would redact somebody else's response.
 */
@jakarta.ws.rs.ext.Provider
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class RedactionWriterInterceptor implements WriterInterceptor {

    private final @NonNull RedactionService redactionService;
    private final @NonNull Provider<RequestContext> requestContext;

    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
        if (!redactionService.isEnabled() || !shouldRedact()) {
            context.proceed();
            return;
        }

        RedactionContext.set(redactionService.rules());
        try {
            context.proceed();
        } finally {
            RedactionContext.clear();
        }
    }

    private boolean shouldRedact() {
        try {
            return requestContext.get().isRedactResponse();
        } catch (RuntimeException outsideRequestScope) {
            // Deliberately not redacting, and deliberately not symmetric with Streamer.
            //
            // RequestContext is @RequestScoped and thread-bound, so this throws on any thread that is not the
            // request thread - including the reactor thread that resumes a @Suspended AsyncResponse, as
            // LocalRunnersResource.nextJob does. Redacting here would rewrite those payloads for every caller,
            // permitted ones included, because the permission is never consulted on that path.
            //
            // Streamer can fail closed because it resolves the decision on the request thread and carries it.
            // An interceptor cannot: by the time it runs the decision is either on the context or unavailable.
            // Fixing this properly means carrying the resolved decision to the write, not guessing at it here.
            return false;
        }
    }
}
