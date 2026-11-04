package com.comet.opik.infrastructure.auth;

import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.util.function.Function;

@RequiredArgsConstructor(access = AccessLevel.PACKAGE)
public final class RequestOrganizationLookup {

    private final @NonNull CometCredentials credentials;
    private Mono<String> organization;

    synchronized Mono<String> organization(@NonNull Function<CometCredentials, Mono<String>> lookup) {
        if (organization == null) {
            organization = lookup.apply(credentials).cache();
        }
        return organization;
    }

    @Override
    public String toString() {
        return "RequestOrganizationLookup";
    }
}
