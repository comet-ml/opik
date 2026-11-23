package com.comet.opik.infrastructure.auth;

import reactor.core.publisher.Mono;

public interface OrganizationResolver {

    /**
     * The organization of the current request or background job.
     * <p>
     * Emits the organization ID, or completes empty when it is unknown: the workspace has no Comet organization, the
     * lookup is off or failed, or the context holds no workspace. It never errors, so callers can fall back to
     * workspace-level behaviour. Without Comet it always emits the default organization.
     */
    Mono<String> organizationId();

    /**
     * Checks that the caller of the current request is an admin of its workspace's organization, and emits that
     * organization's ID, so a write can be scoped to the organization it was authorized for.
     * <p>
     * Errors with {@link jakarta.ws.rs.ForbiddenException} (403) when the caller isn't an admin or the organization is
     * unknown, and with {@link jakarta.ws.rs.ServiceUnavailableException} (503) when Comet can't be asked. Without
     * Comet there are no roles, so every caller passes and gets the default organization.
     */
    Mono<String> requireOrganizationAdmin();
}
