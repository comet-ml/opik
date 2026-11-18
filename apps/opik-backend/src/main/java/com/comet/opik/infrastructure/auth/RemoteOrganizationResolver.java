package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.ProjectService;
import jakarta.ws.rs.ForbiddenException;
import reactor.core.publisher.Mono;

class RemoteOrganizationResolver implements OrganizationResolver {

    static final String UNKNOWN_ORGANIZATION = "The organization of this workspace is unknown, so its organization "
            + "settings can't be changed";

    @Override
    public Mono<String> organizationId() {
        return Mono.deferContextual(ctx -> Mono.justOrEmpty(ctx.<String>getOrEmpty(RequestContext.ORGANIZATION_ID)
                .filter(organizationId -> !ProjectService.DEFAULT_ORGANIZATION_ID.equals(organizationId))));
    }

    @Override
    public Mono<String> requireOrganizationAdmin() {
        return Mono.error(() -> new ForbiddenException(UNKNOWN_ORGANIZATION));
    }
}
