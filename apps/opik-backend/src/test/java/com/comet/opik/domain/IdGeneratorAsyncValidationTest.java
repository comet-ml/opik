package com.comet.opik.domain;

import com.comet.opik.api.error.InvalidUUIDException;
import com.comet.opik.infrastructure.UuidValidationConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TestUuidV7TimestampValidatorFactory;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * Covers the reactive validation paths of {@link IdGenerator} (which the sync {@link
 * UuidV7TimestampValidatorTest} does not): {@code validateIdAsync} / {@code validateIdNotInFutureAsync}
 * resolve {@code workspaceId} from the Reactor context via {@code deferContextual}, falling back to
 * {@link com.comet.opik.infrastructure.metrics.ErrorMetricsResolver#UNKNOWN} when the context has no
 * {@link RequestContext#WORKSPACE_ID}. Both cases must preserve the accept/reject behavior.
 */
@DisplayName("IdGenerator reactive validation")
class IdGeneratorAsyncValidationTest {

    private static final String RESOURCE = "trace";
    private static final Duration WINDOW = Duration.hours(24);

    // Reject mode: enabled=true, auditOnly=false (config-test default).
    private final IdGenerator rejectGenerator = TestIdGeneratorFactory.create();
    // Audit mode: enabled=true, auditOnly=true.
    private final IdGenerator auditGenerator = new IdGeneratorImpl(TestUuidV7TimestampValidatorFactory.create(
            UuidValidationConfig.builder().enabled(true).auditOnly(true).window(WINDOW).build()));

    private UUID idAt(Instant instant) {
        return rejectGenerator.getTimeOrderedEpoch(instant.toEpochMilli());
    }

    private UUID inWindowId() {
        return idAt(Instant.now());
    }

    private UUID tooFarFutureId() {
        return idAt(Instant.now().plus(48, ChronoUnit.HOURS));
    }

    private static Context withWorkspace() {
        return Context.of(RequestContext.WORKSPACE_ID, "ws-async");
    }

    @Test
    @DisplayName("reject: validateIdAsync passes an in-window id through (workspace in context)")
    void rejectAsyncAcceptsInWindow() {
        var id = inWindowId();
        StepVerifier.create(rejectGenerator.validateIdAsync(id, RESOURCE).contextWrite(withWorkspace()))
                .expectNext(id)
                .verifyComplete();
    }

    @Test
    @DisplayName("reject: validateIdAsync rejects a too-far-future id (workspace in context)")
    void rejectAsyncRejectsFuture() {
        StepVerifier.create(rejectGenerator.validateIdAsync(tooFarFutureId(), RESOURCE).contextWrite(withWorkspace()))
                .expectError(InvalidUUIDException.class)
                .verify();
    }

    @Test
    @DisplayName("reject: validateIdAsync still rejects when the context has no workspace id")
    void rejectAsyncRejectsFutureWithoutContext() {
        StepVerifier.create(rejectGenerator.validateIdAsync(tooFarFutureId(), RESOURCE))
                .expectError(InvalidUUIDException.class)
                .verify();
    }

    @Test
    @DisplayName("reject: validateIdNotInFutureAsync rejects only too-far-future, accepts old ids")
    void rejectForUpdateAsync() {
        var oldId = idAt(Instant.now().minus(48, ChronoUnit.HOURS));
        StepVerifier.create(rejectGenerator.validateIdNotInFutureAsync(oldId, RESOURCE).contextWrite(withWorkspace()))
                .expectNext(oldId)
                .verifyComplete();
        StepVerifier
                .create(rejectGenerator.validateIdNotInFutureAsync(tooFarFutureId(), RESOURCE)
                        .contextWrite(withWorkspace()))
                .expectError(InvalidUUIDException.class)
                .verify();
    }

    @Test
    @DisplayName("audit: validateIdAsync passes a too-far-future id through, with and without workspace context")
    void auditAsyncNeverRejects() {
        var withCtxId = tooFarFutureId();
        StepVerifier.create(auditGenerator.validateIdAsync(withCtxId, RESOURCE).contextWrite(withWorkspace()))
                .expectNext(withCtxId)
                .verifyComplete();

        var noCtxId = tooFarFutureId();
        StepVerifier.create(auditGenerator.validateIdAsync(noCtxId, RESOURCE))
                .expectNext(noCtxId)
                .verifyComplete();
    }

    @Test
    @DisplayName("audit: validateIdNotInFutureAsync passes a too-far-future id through")
    void auditForUpdateAsyncNeverRejects() {
        var id = tooFarFutureId();
        StepVerifier.create(Mono.defer(() -> auditGenerator.validateIdNotInFutureAsync(id, RESOURCE)))
                .expectNext(id)
                .verifyComplete();
    }
}
