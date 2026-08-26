package com.comet.opik.infrastructure.redaction;

import jakarta.inject.Inject;
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

    @Override
    public void aroundWriteTo(WriterInterceptorContext context) throws IOException, WebApplicationException {
        if (!redactionService.isEnabled() || !shouldRedact(context)) {
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

    /**
     * Reads the decision {@link RedactionRequestFilter} resolved for this request, rather than resolving one
     * here.
     * <p>
     * The property is on the request, not on a thread, which is the whole point: a {@code @Suspended
     * AsyncResponse} is written from the thread that resumes it — a reactor thread, for the local-runner
     * long-polls — and the {@code @RequestScoped} context is not resolvable there. Reading the context instead
     * threw on exactly those responses, so they were written as stored however the permission had come out.
     * <p>
     * Absent means no decision was resolved for this response: the feature is off, or the path is outside
     * {@code COVERED_PATHS}, and neither is a response to redact.
     */
    private boolean shouldRedact(WriterInterceptorContext context) {
        return Boolean.TRUE.equals(context.getProperty(RedactionRequestFilter.REDACT_RESPONSE_PROPERTY));
    }
}
