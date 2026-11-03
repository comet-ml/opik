package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.ProjectService;
import jakarta.ws.rs.ForbiddenException;
import reactor.core.publisher.Mono;

class RemoteOrganizationResolver implements OrganizationResolver {

    static final String UNKNOWN_ORGANIZATION = "The organization of this workspace is unknown, so its organization "
            + "settings can't be changed";

    // A background job has no caller to ask Comet about, so it carries the ID from its queue message in the context.
    // The default organization is never one of Comet's: seen here, it came from an install that ran without Comet.
    @Override
    public Mono<String> organizationId() {
        return Mono.deferContextual(ctx -> Mono.justOrEmpty(ctx.<String>getOrEmpty(RequestContext.ORGANIZATION_ID)
                .filter(organizationId -> !ProjectService.DEFAULT_ORGANIZATION_ID.equals(organizationId))));
    }

    // Never answered from the context: an ID a job carried says nothing about whether this caller is an admin.
    @Override
    public Mono<String> requireOrganizationAdmin() {
        return Mono.error(() -> new ForbiddenException(UNKNOWN_ORGANIZATION));
    }
}
