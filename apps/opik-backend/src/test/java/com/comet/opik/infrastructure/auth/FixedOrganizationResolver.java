package com.comet.opik.infrastructure.auth;

import jakarta.ws.rs.ForbiddenException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@RequiredArgsConstructor
public class FixedOrganizationResolver implements OrganizationResolver {

    public static final FixedOrganizationResolver NONE = new FixedOrganizationResolver(Mono.empty());

    private final @NonNull Mono<String> organizationId;

    public static FixedOrganizationResolver of(@NonNull String organizationId) {
        return new FixedOrganizationResolver(Mono.just(organizationId));
    }

    @Override
    public Mono<String> organizationId() {
        return organizationId;
    }

    @Override
    public Mono<String> requireOrganizationAdmin() {
        return Mono.error(ForbiddenException::new);
    }
}
