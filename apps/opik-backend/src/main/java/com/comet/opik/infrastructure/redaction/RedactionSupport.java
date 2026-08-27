package com.comet.opik.infrastructure.redaction;

import com.comet.opik.infrastructure.auth.RequestContext;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.reactivestreams.Publisher;
import reactor.core.publisher.Flux;

import java.util.function.Function;

/**
 * The single place the per-request masking decision is read, so every read path resolves it the same way.
 * <p>
 * Row mapping runs long after the resource method returned, on whichever thread the database driver hands us, so
 * the decision cannot be passed as an argument and is carried on the reactive context instead — the same route
 * {@code workspaceId} and {@code visibility} already take. Absent from the context means no masking: the
 * overloads of {@code AsyncUtils.setRequestContext} that omit it are the ones used by background jobs, which have
 * no caller to withhold content from and whose output feeds internal processing rather than a response.
 * <p>
 * Deliberately the only reader of {@link RequestContext#REDACT_RESPONSE}: a second one would be a second policy.
 */
@UtilityClass
public class RedactionSupport {

    /**
     * Resolves the masker once per result set and hands it to the row mapper.
     * <p>
     * Once per result set rather than once per row because the decision is a property of the request, and once per
     * row would re-read the context for every row of a page.
     */
    public static <T> Flux<T> masked(@NonNull RedactionService redactionService,
            @NonNull Function<FieldMasker, Publisher<T>> mapper) {

        return Flux.deferContextual(ctx -> {
            FieldMasker masker = ctx.getOrDefault(RequestContext.REDACT_RESPONSE, Boolean.FALSE)
                    ? redactionService.masker()
                    : FieldMasker.noOp();

            return Flux.from(mapper.apply(masker));
        });
    }
}
