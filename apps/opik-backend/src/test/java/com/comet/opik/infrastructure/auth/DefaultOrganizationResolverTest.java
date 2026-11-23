package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.ProjectService;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.UUID;
import java.util.stream.Stream;

class DefaultOrganizationResolverTest {

    private static final String PINNED_DEFAULT_ORGANIZATION_ID = "01a1a1c9-b569-7b5b-9c5c-1aa254d42e50";

    private final OrganizationResolver resolver = new DefaultOrganizationResolver();

    static Stream<Named<Context>> contexts() {
        return Stream.of(
                Named.of("no context", Context.empty()),
                Named.of("default workspace request", Context.of(
                        RequestContext.USER_NAME, ProjectService.DEFAULT_USER,
                        RequestContext.WORKSPACE_ID, ProjectService.DEFAULT_WORKSPACE_ID,
                        RequestContext.WORKSPACE_NAME, ProjectService.DEFAULT_WORKSPACE_NAME)),
                Named.of("job carrying another organization", Context.of(
                        RequestContext.WORKSPACE_ID, ProjectService.DEFAULT_WORKSPACE_ID,
                        RequestContext.ORGANIZATION_ID, UUID.randomUUID().toString())));
    }

    @ParameterizedTest
    @MethodSource("contexts")
    void organizationIdReturnsDefaultOrganization(Context context) {
        StepVerifier.create(resolver.organizationId().contextWrite(context))
                .expectNext(PINNED_DEFAULT_ORGANIZATION_ID)
                .verifyComplete();
    }

    @ParameterizedTest
    @MethodSource("contexts")
    void requireOrganizationAdminAllowsWithDefaultOrganization(Context context) {
        StepVerifier.create(resolver.requireOrganizationAdmin().contextWrite(context))
                .expectNext(PINNED_DEFAULT_ORGANIZATION_ID)
                .verifyComplete();
    }
}
