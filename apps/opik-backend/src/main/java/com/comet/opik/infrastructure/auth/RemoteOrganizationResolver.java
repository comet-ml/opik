package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.auth.WorkspaceOrganizationClient.Denied;
import com.comet.opik.infrastructure.auth.WorkspaceOrganizationClient.Found;
import com.comet.opik.infrastructure.auth.WorkspaceOrganizationClient.Lookup;
import com.comet.opik.infrastructure.auth.WorkspaceOrganizationClient.NoOrganization;
import com.comet.opik.utils.RetryUtils;
import com.google.inject.OutOfScopeException;
import com.google.inject.ProvisionException;
import jakarta.inject.Provider;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.WebApplicationException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

import java.util.Optional;

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

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull WorkspaceOrganizationClient client;
    private final @NonNull WorkspaceOrganizationCache cache;
    private final boolean organizationIdLookupEnabled;

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
                    .map(credentials -> resolve(workspaceId.get(), workspaceName, credentials))
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

    private Mono<String> resolve(String workspaceId, String workspaceName, CometCredentials credentials) {
        return cache.find(workspaceId)
                .map(Optional::of)
                .defaultIfEmpty(Optional.empty())
                .flatMap(cached -> cached.filter(entry -> entry.isFresh(cache.now())).isPresent()
                        ? Mono.justOrEmpty(cached.map(WorkspaceOrganizationCache.Entry::organizationId))
                        : ask(workspaceId, workspaceName, credentials, cached))
                .onErrorResume(error -> {
                    log.warn("Could not resolve the organization, workspace '{}'", workspaceId, error);
                    return Mono.empty();
                });
    }

    private Mono<String> ask(String workspaceId, String workspaceName, CometCredentials credentials,
            Optional<WorkspaceOrganizationCache.Entry> cached) {
        var cachedOrganization = Mono.justOrEmpty(cached.map(WorkspaceOrganizationCache.Entry::organizationId));
        return client.fetch(credentials, workspaceName)
                .flatMap(lookup -> remember(workspaceId, lookup).then(switch (lookup) {
                    case Found found -> Mono.just(found.organizationId());
                    case NoOrganization noOrganization -> Mono.<String>empty();
                    case Denied denied -> cachedOrganization;
                }))
                .onErrorResume(cometFailed -> {
                    logCometFailed(ORGANIZATION_LOOKUP, workspaceId, cometFailed);
                    return cached.isPresent() ? retryLater(workspaceId).then(cachedOrganization) : Mono.empty();
                });
    }

    private Mono<Void> retryLater(String workspaceId) {
        return cache.retryLater(workspaceId).onErrorResume(error -> {
            log.warn("Could not delay the next Comet call, workspace '{}'", workspaceId, error);
            return Mono.empty();
        });
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
