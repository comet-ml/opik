package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.ProjectService;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.stream.Stream;

class DefaultOrganizationResolverTest {

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
                        RequestContext.ORGANIZATION_ID, "another-organization")));
    }

    @ParameterizedTest
    @MethodSource("contexts")
    void organizationIdReturnsDefaultOrganization(Context context) {
        StepVerifier.create(resolver.organizationId().contextWrite(context))
                .expectNext(ProjectService.DEFAULT_ORGANIZATION_ID)
                .verifyComplete();
    }

    @Test
    void requireOrganizationAdminAllowsWithDefaultOrganization() {
        StepVerifier.create(resolver.requireOrganizationAdmin())
                .expectNext(ProjectService.DEFAULT_ORGANIZATION_ID)
                .verifyComplete();
    }
}
