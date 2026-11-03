package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.ProjectService;
import reactor.core.publisher.Mono;

class DefaultOrganizationResolver implements OrganizationResolver {

    @Override
    public Mono<String> organizationId() {
        return Mono.just(ProjectService.DEFAULT_ORGANIZATION_ID);
    }

    @Override
    public Mono<String> requireOrganizationAdmin() {
        return organizationId();
    }
}
