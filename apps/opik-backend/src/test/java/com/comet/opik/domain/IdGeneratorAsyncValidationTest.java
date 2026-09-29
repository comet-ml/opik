package com.comet.opik.domain;

import com.comet.opik.api.error.InvalidUUIDException;
import com.comet.opik.infrastructure.UuidValidationConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TestUuidV7TimestampValidatorFactory;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * Covers the reactive validation paths of {@link IdGenerator} (which the sync
 * {@code UuidV7TimestampValidatorTest} does not): {@code validateIdAsync} / {@code validateIdNotInFutureAsync}
 * resolve {@code workspaceId} from the Reactor context via {@code deferContextual}, falling back to
 * {@link com.comet.opik.infrastructure.metrics.ErrorMetricsResolver#UNKNOWN} when the context has no
 * {@link RequestContext#WORKSPACE_ID}. Both cases must preserve the accept/reject behavior.
 *
 * <p>The same context lookup is what makes the workspace-scoped bypass (OPIK-7794) reach the reactive
 * ingestion paths, so the bypass is covered here too.
 */
@DisplayName("IdGenerator reactive validation")
class IdGeneratorAsyncValidationTest {

    private static final String RESOURCE = "trace";
    private static final Duration WINDOW = Duration.hours(24);
    private static final Duration BYPASS_WINDOW = Duration.days(30);
    private static final String BYPASS_WORKSPACE_ID = UUID.randomUUID().toString();
    /**
     * Offsets either side of BYPASS_WINDOW, both outside WINDOW.
     */
    private static final int WITHIN_BYPASS_WINDOW_DAYS = 10;
    private static final int BEYOND_BYPASS_WINDOW_DAYS = 40;

    /**
     * Reject mode: enabled=true, auditOnly=false (config-test default).
     */
    private static final IdGenerator REJECT_GENERATOR = TestIdGeneratorFactory.create();
    /**
     * Audit mode: enabled=true, auditOnly=true.
     */
    private static final IdGenerator AUDIT_GENERATOR = new IdGeneratorImpl(
            TestUuidV7TimestampValidatorFactory.create(config().auditOnly(true).build()));
    /**
     * Reject mode with BYPASS_WORKSPACE_ID allow-listed for the wider bypass window.
     */
    private static final IdGenerator BYPASS_GENERATOR = new IdGeneratorImpl(
            TestUuidV7TimestampValidatorFactory.create(config().build(), BYPASS_WORKSPACE_ID));

    /**
     * Reject mode over the default windows, for callers to override only what their case is about.
     */
    private static UuidValidationConfig.UuidValidationConfigBuilder config() {
        return UuidValidationConfig.builder()
                .enabled(true)
                .auditOnly(false)
                .window(WINDOW)
                .bypassWindow(BYPASS_WINDOW);
    }

    private UUID idAt(Instant instant) {
        return REJECT_GENERATOR.getTimeOrderedEpoch(instant.toEpochMilli());
    }

    private UUID inWindowId() {
        return idAt(Instant.now());
    }

    private UUID tooFarFutureId() {
        return idAt(Instant.now().plus(48, ChronoUnit.HOURS));
    }

    private static Context withWorkspace() {
        return withWorkspace("ws-async");
    }

    private static Context withWorkspace(String workspaceId) {
        return Context.of(RequestContext.WORKSPACE_ID, workspaceId);
    }

    @Test
    @DisplayName("reject: validateIdAsync passes an in-window id through (workspace in context)")
    void rejectAsyncAcceptsInWindow() {
        var id = inWindowId();
        StepVerifier.create(REJECT_GENERATOR.validateIdAsync(id, RESOURCE).contextWrite(withWorkspace()))
                .expectNext(id)
                .verifyComplete();
    }

    @Test
    @DisplayName("reject: validateIdAsync rejects a too-far-future id (workspace in context)")
    void rejectAsyncRejectsFuture() {
        StepVerifier.create(REJECT_GENERATOR.validateIdAsync(tooFarFutureId(), RESOURCE).contextWrite(withWorkspace()))
                .expectError(InvalidUUIDException.class)
                .verify();
    }

    @Test
    @DisplayName("reject: validateIdAsync still rejects when the context has no workspace id")
    void rejectAsyncRejectsFutureWithoutContext() {
        StepVerifier.create(REJECT_GENERATOR.validateIdAsync(tooFarFutureId(), RESOURCE))
                .expectError(InvalidUUIDException.class)
                .verify();
    }

    @Test
    @DisplayName("reject: validateIdNotInFutureAsync rejects only too-far-future, accepts old ids")
    void rejectForUpdateAsync() {
        var oldId = idAt(Instant.now().minus(48, ChronoUnit.HOURS));
        StepVerifier.create(REJECT_GENERATOR.validateIdNotInFutureAsync(oldId, RESOURCE).contextWrite(withWorkspace()))
                .expectNext(oldId)
                .verifyComplete();
        StepVerifier
                .create(REJECT_GENERATOR.validateIdNotInFutureAsync(tooFarFutureId(), RESOURCE)
                        .contextWrite(withWorkspace()))
                .expectError(InvalidUUIDException.class)
                .verify();
    }

    @Test
    @DisplayName("audit: validateIdAsync passes a too-far-future id through, with and without workspace context")
    void auditAsyncNeverRejects() {
        var withCtxId = tooFarFutureId();
        StepVerifier.create(AUDIT_GENERATOR.validateIdAsync(withCtxId, RESOURCE).contextWrite(withWorkspace()))
                .expectNext(withCtxId)
                .verifyComplete();

        var noCtxId = tooFarFutureId();
        StepVerifier.create(AUDIT_GENERATOR.validateIdAsync(noCtxId, RESOURCE))
                .expectNext(noCtxId)
                .verifyComplete();
    }

    @Test
    @DisplayName("audit: validateIdNotInFutureAsync passes a too-far-future id through")
    void auditForUpdateAsyncNeverRejects() {
        var id = tooFarFutureId();
        StepVerifier.create(Mono.defer(() -> AUDIT_GENERATOR.validateIdNotInFutureAsync(id, RESOURCE)))
                .expectNext(id)
                .verifyComplete();
    }

    @Test
    void bypassAcceptsAnOutOfWindowIdForTheAllowListedWorkspaceInContext() {
        var id = bypassableId();
        StepVerifier
                .create(BYPASS_GENERATOR.validateIdAsync(id, RESOURCE).contextWrite(withWorkspace(BYPASS_WORKSPACE_ID)))
                .expectNext(id)
                .verifyComplete();
    }

    @Test
    void bypassAcceptsAFutureDatedReferencedIdForTheAllowListedWorkspaceInContext() {
        var id = bypassableId();
        StepVerifier.create(BYPASS_GENERATOR.validateIdNotInFutureAsync(id, RESOURCE)
                .contextWrite(withWorkspace(BYPASS_WORKSPACE_ID)))
                .expectNext(id)
                .verifyComplete();
    }

    /**
     * The ways an id is still rejected despite the allow-list: a different workspace, no workspace at all,
     * and an offset beyond the bypass window.
     */
    static Stream<Arguments> bypassRejects() {
        return Stream.of(
                arguments(WITHIN_BYPASS_WINDOW_DAYS, withWorkspace()),
                arguments(WITHIN_BYPASS_WINDOW_DAYS, Context.empty()),
                arguments(BEYOND_BYPASS_WINDOW_DAYS, withWorkspace(BYPASS_WORKSPACE_ID)));
    }

    @ParameterizedTest
    @MethodSource
    void bypassRejects(int daysFromNow, Context context) {
        var id = idAt(Instant.now().plus(daysFromNow, ChronoUnit.DAYS));
        StepVerifier.create(BYPASS_GENERATOR.validateIdAsync(id, RESOURCE).contextWrite(context))
                .expectError(InvalidUUIDException.class)
                .verify();
    }

    @Test
    void bypassAcceptsAnOldReferencedIdBeyondTheBypassWindow() {
        // Referenced ids only ever fail on the future side, so the bypass window never gates an old one.
        var id = idAt(Instant.now().minus(BEYOND_BYPASS_WINDOW_DAYS, ChronoUnit.DAYS));
        StepVerifier.create(BYPASS_GENERATOR.validateIdNotInFutureAsync(id, RESOURCE)
                .contextWrite(withWorkspace(BYPASS_WORKSPACE_ID)))
                .expectNext(id)
                .verifyComplete();
    }

    /**
     * An id outside the default window but inside the bypass window, so it is accepted only for an
     * allow-listed workspace.
     */
    private UUID bypassableId() {
        return idAt(Instant.now().plus(WITHIN_BYPASS_WINDOW_DAYS, ChronoUnit.DAYS));
    }
}
