package com.comet.opik.infrastructure.auth;

import reactor.core.publisher.Mono;

public interface OrganizationResolver {

    Mono<String> organizationId();

    Mono<String> requireOrganizationAdmin();
}
