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
            // No caller to decide against. See RedactionService.redactWhenCallerUnknown for why this redacts
            // rather than writing as stored, and why both paths now consult one definition of it.
            return redactionService.redactWhenCallerUnknown();
        }
    }
}
