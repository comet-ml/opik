package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.auth.WorkspaceOrganizationClient.Denied;
import com.comet.opik.infrastructure.auth.WorkspaceOrganizationClient.Found;
import com.comet.opik.infrastructure.auth.WorkspaceOrganizationClient.Lookup;
import com.comet.opik.infrastructure.auth.WorkspaceOrganizationClient.NoOrganization;
import com.google.inject.OutOfScopeException;
import com.google.inject.ProvisionException;
import jakarta.inject.Provider;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.ServiceUnavailableException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;

import java.util.Optional;

@Slf4j
@RequiredArgsConstructor
class RemoteOrganizationResolver implements OrganizationResolver {

    static final String UNKNOWN_ORGANIZATION = "The organization of this workspace is unknown, so its organization "
            + "settings can't be changed";
    static final String NOT_ORGANIZATION_ADMIN = "Only organization admins can change organization settings";
    static final String ADMIN_CHECK_FAILED = "Could not check whether you are an organization admin, please try "
            + "again";

    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull WorkspaceOrganizationClient client;
    private final @NonNull WorkspaceOrganizationCache cache;
    private final boolean lookupEnabled;

    @Override
    public Mono<String> organizationId() {
        return Mono.deferContextual(ctx -> ctx.<String>getOrEmpty(RequestContext.ORGANIZATION_ID)
                .map(Mono::just)
                .orElseGet(() -> ctx.<String>getOrEmpty(RequestContext.WORKSPACE_ID)
                        .filter(workspaceId -> lookupEnabled)
                        .map(cache::organizationId)
                        .orElseGet(Mono::empty)))
                .filter(organizationId -> !ProjectService.DEFAULT_ORGANIZATION_ID.equals(organizationId));
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
                        log.warn("Organization admin check failed, workspace '{}'", context.getWorkspaceId(), error);
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

    @Override
    public void resolveRequestOrganization() {
        if (!lookupEnabled) {
            return;
        }
        var context = requestContext.get();
        if (StringUtils.isBlank(context.getWorkspaceId())
                || ProjectService.DEFAULT_WORKSPACE_NAME.equalsIgnoreCase(context.getWorkspaceName())) {
            return;
        }
        try {
            context.setOrganizationId(resolve(context));
        } catch (RuntimeException error) {
            log.warn("Could not resolve the organization of workspace '{}'", context.getWorkspaceId(), error);
        }
    }

    private String resolve(RequestContext context) {
        var workspaceId = context.getWorkspaceId();
        var cached = cache.find(workspaceId).blockOptional();
        if (cached.map(entry -> entry.isFresh(cache.now())).orElse(false)
                || context.getCometCredentials() == null
                || StringUtils.isBlank(context.getWorkspaceName())) {
            return cached.map(WorkspaceOrganizationCache.Entry::organizationId).orElse(null);
        }
        try {
            var lookup = client.fetch(context.getCometCredentials(), context.getWorkspaceName()).block();
            remember(workspaceId, lookup).block();
            return lookup instanceof Found found
                    ? found.organizationId()
                    : cached.map(WorkspaceOrganizationCache.Entry::organizationId).orElse(null);
        } catch (RuntimeException cometFailed) {
            log.warn("Comet could not resolve the organization of workspace '{}', using the cached one",
                    workspaceId, cometFailed);
            cached.ifPresent(entry -> cache.retryLater(workspaceId).block());
            return cached.map(WorkspaceOrganizationCache.Entry::organizationId).orElse(null);
        }
    }

    private Mono<Void> remember(String workspaceId, Lookup lookup) {
        var save = switch (lookup) {
            case Found found -> cache.save(workspaceId, found.organizationId());
            case NoOrganization noOrganization -> cache.save(workspaceId, null);
            case Denied denied -> Mono.<Void>empty();
        };
        return save.onErrorResume(error -> {
            log.warn("Could not cache the organization of workspace '{}'", workspaceId, error);
            return Mono.empty();
        });
    }

    private Optional<RequestContext> currentRequest() {
        try {
            return Optional.of(requestContext.get());
        } catch (OutOfScopeException | ProvisionException notInARequest) {
            return Optional.empty();
        }
    }
}
