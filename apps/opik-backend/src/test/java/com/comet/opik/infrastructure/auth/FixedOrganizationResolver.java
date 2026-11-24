package com.comet.opik.infrastructure.auth;

import jakarta.ws.rs.ForbiddenException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.util.concurrent.atomic.AtomicInteger;

@RequiredArgsConstructor
public class FixedOrganizationResolver implements OrganizationResolver {

    private final @NonNull Mono<String> organizationId;
    private final AtomicInteger lookups = new AtomicInteger();

    public static FixedOrganizationResolver none() {
        return new FixedOrganizationResolver(Mono.empty());
    }

    public static FixedOrganizationResolver of(@NonNull String organizationId) {
        return new FixedOrganizationResolver(Mono.just(organizationId));
    }

    @Override
    public Mono<String> organizationId() {
        return Mono.defer(() -> {
            lookups.incrementAndGet();
            return organizationId;
        });
    }

    @Override
    public Mono<String> requireOrganizationAdmin() {
        return Mono.error(ForbiddenException::new);
    }

    public int lookups() {
        return lookups.get();
    }
}
