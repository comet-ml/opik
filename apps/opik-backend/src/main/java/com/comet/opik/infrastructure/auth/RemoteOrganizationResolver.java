package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.auth.WorkspaceOrganizationClient.Denied;
import com.comet.opik.infrastructure.auth.WorkspaceOrganizationClient.Found;
import com.comet.opik.infrastructure.auth.WorkspaceOrganizationClient.Lookup;
import com.comet.opik.infrastructure.auth.WorkspaceOrganizationClient.NoOrganization;
import com.comet.opik.infrastructure.db.HasValue;
import com.comet.opik.infrastructure.metrics.ErrorMetricsResolver;
import com.comet.opik.utils.RetryUtils;
import com.google.inject.OutOfScopeException;
import com.google.inject.ProvisionException;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.AttributesBuilder;
import io.opentelemetry.api.metrics.LongCounter;
import io.opentelemetry.api.metrics.Meter;
import jakarta.inject.Provider;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.WebApplicationException;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.util.context.ContextView;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@RequiredArgsConstructor
class RemoteOrganizationResolver implements OrganizationResolver {

    static final String ADMIN_CHECK = "admin check";
    static final String ORGANIZATION_LOOKUP = "organization lookup";
    static final String UNKNOWN_ORGANIZATION = """
            The organization of this workspace is unknown, so its organization settings can't be changed\
            """;
    static final String NOT_ORGANIZATION_ADMIN = "Only organization admins can change organization settings";
    static final String ADMIN_CHECK_FAILED = """
            Could not check whether you are an organization admin, please try again\
            """;

    private static final String METRIC_NAMESPACE = "organization_lookup";
    private static final AttributeKey<String> SOURCE_KEY = AttributeKey.stringKey("source");
    private static final AttributeKey<String> RESULT_KEY = AttributeKey.stringKey("result");
    private static final AttributeKey<String> ANSWER_KEY = AttributeKey.stringKey("answer");

    @Getter
    @RequiredArgsConstructor
    private enum AnswerSource implements HasValue {
        FRESH_ENTRY("fresh_entry"),
        STALE_ENTRY("stale_entry"),
        FAILURE_MARKER("failure_marker"),
        COMET("comet");

        private final String value;
    }

    @Getter
    @RequiredArgsConstructor
    private enum CometCallResult implements HasValue {
        SUCCESS("success"),
        ERROR("error");

        private final String value;
    }

    @Builder(toBuilder = true)
    private record Caller(@NonNull String workspaceId, @NonNull String workspaceName,
            @NonNull CometCredentials credentials, @NonNull ContextView context) {
    }

    @Builder(toBuilder = true)
    private record SharedLookup(@NonNull CometCredentials credentials, @NonNull Sinks.One<Lookup> answer) {
    }

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull WorkspaceOrganizationClient client;
    private final @NonNull WorkspaceOrganizationCache cache;
    private final boolean organizationIdLookupEnabled;
    private final ConcurrentMap<String, SharedLookup> lookupsInFlight = new ConcurrentHashMap<>();
    private final Meter meter = GlobalOpenTelemetry.getMeter(METRIC_NAMESPACE);
    private final LongCounter answers = meter.counterBuilder("%s_answers_total".formatted(METRIC_NAMESPACE))
            .setDescription("""
                    Organization answers for requests with Comet credentials, by workspace and source: fresh_entry, \
                    stale_entry (served while it is refreshed in the background), failure_marker (none until the \
                    retry window ends) or comet (waited for a lookup, shared or its own)""")
            .build();
    private final LongCounter cometCalls = meter.counterBuilder("%s_comet_calls_total".formatted(METRIC_NAMESPACE))
            .setDescription("""
                    Organization lookups sent to Comet, by workspace and result: success with its answer \
                    (found / no_organization / denied), or error with its error_type""")
            .build();

    @Override
    public Mono<String> organizationId() {
        return Mono.deferContextual(ctx -> {
            Optional<String> carried = ctx.getOrEmpty(RequestContext.ORGANIZATION_ID);
            if (carried.isPresent()) {
                return Mono.just(carried.get());
            }
            Optional<String> workspaceId = ctx.<String>getOrEmpty(RequestContext.WORKSPACE_ID)
                    .filter(StringUtils::isNotBlank);
            if (!organizationIdLookupEnabled || workspaceId.isEmpty()) {
                return Mono.empty();
            }
            String workspaceName = ctx.getOrDefault(RequestContext.WORKSPACE_NAME, null);
            return ctx.<CometCredentials>getOrEmpty(RequestContext.COMET_CREDENTIALS)
                    .filter(credentials -> StringUtils.isNotBlank(workspaceName)
                            && !ProjectService.DEFAULT_WORKSPACE_NAME.equalsIgnoreCase(workspaceName))
                    .map(credentials -> resolve(Caller.builder()
                            .workspaceId(workspaceId.get())
                            .workspaceName(workspaceName)
                            .credentials(credentials)
                            .context(ctx)
                            .build()))
                    .orElseGet(() -> cache.organizationId(workspaceId.get()));
        }).filter(organizationId -> !ProjectService.DEFAULT_ORGANIZATION_ID.equals(organizationId));
    }

    @Override
    public Mono<String> requireOrganizationAdmin() {
        return Mono.defer(() -> {
            var caller = currentRequest()
                    .filter(context -> context.getCometCredentials() != null)
                    .filter(context -> StringUtils.isNoneBlank(context.getWorkspaceId(), context.getWorkspaceName()));
            if (caller.isEmpty()) {
                return Mono.error(() -> new ForbiddenException(UNKNOWN_ORGANIZATION));
            }
            var context = caller.get();
            return client.fetch(context.getCometCredentials(), context.getWorkspaceName())
                    .onErrorMap(error -> {
                        logCometFailed(ADMIN_CHECK, context.getWorkspaceId(), error);
                        return new ServiceUnavailableException(ADMIN_CHECK_FAILED);
                    })
                    .flatMap(lookup -> remember(context.getWorkspaceId(), lookup).thenReturn(lookup))
                    .flatMap(lookup -> switch (lookup) {
                        case Found found when found.isOrganizationAdmin() -> Mono.just(found.organizationId());
                        case Found found -> Mono.error(new ForbiddenException(NOT_ORGANIZATION_ADMIN));
                        case NoOrganization noOrganization ->
                            Mono.error(new ForbiddenException(UNKNOWN_ORGANIZATION));
                        case Denied denied -> Mono.error(new ForbiddenException(UNKNOWN_ORGANIZATION));
                    });
        });
    }

    private Mono<String> resolve(Caller caller) {
        return cache.find(caller.workspaceId())
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(cached -> cached
                        .map(entry -> fromCache(entry, caller))
                        .orElseGet(() -> lookUp(caller)))
                .onErrorResume(error -> {
                    log.warn("Could not resolve the organization, workspace '{}'", caller.workspaceId(), error);
                    return Mono.empty();
                });
    }

    private Mono<String> fromCache(WorkspaceOrganizationCache.Entry entry, Caller caller) {
        if (entry.isFresh(cache.now())) {
            countAnswer(caller, AnswerSource.FRESH_ENTRY);
        } else {
            countAnswer(caller, AnswerSource.STALE_ENTRY);
            refreshInBackground(caller);
        }
        return Mono.justOrEmpty(entry.organizationId());
    }

    /**
     * Nobody waits for the refresh, but its answer is still followed: when the shared refresh ran on credentials that
     * Comet denied, this caller starts the next one, or a caller with a revoked key could keep a stale answer until it
     * expires.
     */
    private void refreshInBackground(Caller caller) {
        lookupFor(caller, false)
                .contextWrite(caller.context())
                .subscribe(null, error -> log.warn("Could not refresh the organization, workspace '{}'",
                        caller.workspaceId(), error));
    }

    private Mono<String> lookUp(Caller caller) {
        return cache.failedRecently(caller.workspaceId())
                .flatMap(failed -> {
                    countAnswer(caller, failed ? AnswerSource.FAILURE_MARKER : AnswerSource.COMET);
                    return failed
                            ? Mono.<String>empty()
                            : lookupFor(caller, false).flatMap(RemoteOrganizationResolver::organizationOf);
                });
    }

    /**
     * Comet denies a caller, not a workspace, so another caller's denial says nothing about these credentials: they
     * start the next shared lookup, and the other callers that denial left without an answer join it. When that one is
     * denied for other credentials too (a denied caller started it first), these credentials ask Comet on their own.
     */
    private Mono<Lookup> lookupFor(Caller caller, boolean deniedForOthersBefore) {
        var lookup = sharedLookup(caller);
        return lookup.answer().asMono().flatMap(answer -> {
            if (!(answer instanceof Denied) || caller.credentials().equals(lookup.credentials())) {
                return Mono.just(answer);
            }
            if (deniedForOthersBefore) {
                return ask(caller);
            }
            // The denied lookup leaves the map only after all its waiters got the answer: dropped here, so this
            // caller starts the next lookup instead of joining the finished one again
            lookupsInFlight.remove(caller.workspaceId(), lookup);
            return lookupFor(caller, true);
        });
    }

    private SharedLookup sharedLookup(Caller caller) {
        var started = SharedLookup.builder()
                .credentials(caller.credentials())
                .answer(Sinks.one())
                .build();
        var running = lookupsInFlight.putIfAbsent(caller.workspaceId(), started);
        if (running != null) {
            return running;
        }
        // Subscribed on its own, not as part of the caller's chain: a caller that goes away (closed connection,
        // timeout) must not cancel a lookup other callers wait on, or the refresh behind a stale answer
        Mono.defer(() -> ask(caller))
                .contextWrite(caller.context())
                .doFinally(signal -> lookupsInFlight.remove(caller.workspaceId(), started))
                .subscribe(started.answer()::tryEmitValue, started.answer()::tryEmitError,
                        started.answer()::tryEmitEmpty);
        return started;
    }

    private Mono<Lookup> ask(Caller caller) {
        return client.fetch(caller.credentials(), caller.workspaceName())
                .doOnNext(lookup -> countCometAnswer(caller, lookup))
                .flatMap(lookup -> remember(caller.workspaceId(), lookup).thenReturn(lookup))
                .onErrorResume(cometFailed -> {
                    logCometFailed(ORGANIZATION_LOOKUP, caller.workspaceId(), cometFailed);
                    countCometError(caller, cometFailed);
                    var delay = WorkspaceOrganizationClient.isCometDown(cometFailed)
                            ? cache.retryLater(caller.workspaceId())
                            : cache.refreshLater(caller.workspaceId());
                    return delay.onErrorResume(error -> {
                        log.warn("Could not delay the next Comet call, workspace '{}'", caller.workspaceId(), error);
                        return Mono.empty();
                    }).then(Mono.empty());
                });
    }

    private void countAnswer(Caller caller, AnswerSource source) {
        answers.add(1, workspaceAttributes(caller).put(SOURCE_KEY, source.getValue()).build());
    }

    private void countCometAnswer(Caller caller, Lookup lookup) {
        var answer = switch (lookup) {
            case Found found -> "found";
            case NoOrganization noOrganization -> "no_organization";
            case Denied denied -> "denied";
        };
        cometCalls.add(1, workspaceAttributes(caller)
                .put(RESULT_KEY, CometCallResult.SUCCESS.getValue())
                .put(ANSWER_KEY, answer)
                .build());
    }

    private void countCometError(Caller caller, Throwable error) {
        cometCalls.add(1, workspaceAttributes(caller)
                .put(RESULT_KEY, CometCallResult.ERROR.getValue())
                .put(ErrorMetricsResolver.ERROR_TYPE_KEY, ErrorMetricsResolver.errorType(error))
                .build());
    }

    private static AttributesBuilder workspaceAttributes(Caller caller) {
        return Attributes.builder()
                .put(ErrorMetricsResolver.WORKSPACE_ID_KEY, caller.workspaceId())
                .put(ErrorMetricsResolver.WORKSPACE_NAME_KEY, caller.workspaceName());
    }

    private static Mono<String> organizationOf(Lookup lookup) {
        return lookup instanceof Found found ? Mono.just(found.organizationId()) : Mono.empty();
    }

    /**
     * Logs a failed Comet call as one line. Comet being down or answering an error is a known failure with its own
     * handling, so its stack trace is only noise; anything else keeps it.
     */
    private static void logCometFailed(String operation, String workspaceId, Throwable error) {
        Integer status = switch (error) {
            case WebApplicationException unexpected -> unexpected.getResponse().getStatus();
            case RetryUtils.RetryableHttpException retryable -> retryable.getStatusCode();
            default -> null;
        };
        if (status != null) {
            log.error("Comet organization call failed, operation '{}', workspace '{}', error type '{}', status '{}'",
                    operation, workspaceId, error.getClass().getSimpleName(), status);
        } else if (error instanceof ProcessingException) {
            log.error("Comet organization call failed, operation '{}', workspace '{}', error type '{}'",
                    operation, workspaceId, error.getClass().getSimpleName());
        } else {
            log.error("Comet organization call failed, operation '{}', workspace '{}'", operation, workspaceId, error);
        }
    }

    private Mono<Void> remember(String workspaceId, Lookup lookup) {
        var save = switch (lookup) {
            case Found found -> cache.save(workspaceId, found.organizationId());
            case NoOrganization noOrganization -> cache.save(workspaceId, null);
            case Denied denied -> Mono.<Void>empty();
        };
        return save.onErrorResume(error -> {
            log.warn("Could not cache the organization, workspace '{}'", workspaceId, error);
            return Mono.empty();
        });
    }

    private Optional<RequestContext> currentRequest() {
        try {
            return Optional.of(requestContext.get());
        } catch (OutOfScopeException | ProvisionException notInARequest) {
            log.warn("Organization admin check outside a request, so there is no caller to check", notInARequest);
            return Optional.empty();
        }
    }
}
