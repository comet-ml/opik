package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.ProjectService;
import reactor.core.publisher.Mono;

// With authentication off, AuthServiceImpl admits only the default workspace, so a single constant organization is
// exact rather than a guess, and like every other action on such an install, editing its settings is open to all.
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
