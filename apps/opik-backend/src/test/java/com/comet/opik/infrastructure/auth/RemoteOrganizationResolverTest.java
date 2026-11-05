package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.ProjectService;
import jakarta.ws.rs.ForbiddenException;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RemoteOrganizationResolverTest {

    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String ORGANIZATION_ID = UUID.randomUUID().toString();

    private static final Context REQUEST = Context.of(
            RequestContext.USER_NAME, "user",
            RequestContext.WORKSPACE_ID, WORKSPACE_ID,
            RequestContext.WORKSPACE_NAME, "workspace");
    private static final Context JOB = Context.of(
            RequestContext.USER_NAME, "user",
            RequestContext.WORKSPACE_ID, WORKSPACE_ID,
            RequestContext.ORGANIZATION_ID, ORGANIZATION_ID);
    private static final Context JOB_FROM_INSTALL_WITHOUT_COMET = Context.of(
            RequestContext.USER_NAME, ProjectService.DEFAULT_USER,
            RequestContext.WORKSPACE_ID, ProjectService.DEFAULT_WORKSPACE_ID,
            RequestContext.ORGANIZATION_ID, ProjectService.DEFAULT_ORGANIZATION_ID);

    private final OrganizationResolver resolver = new RemoteOrganizationResolver();

    @Test
    void organizationIdWhenRequestReturnsEmpty() {
        StepVerifier.create(resolver.organizationId().contextWrite(REQUEST))
                .verifyComplete();
    }

    @Test
    void organizationIdWhenJobCarriesOrganizationReturnsIt() {
        StepVerifier.create(resolver.organizationId().contextWrite(JOB))
                .expectNext(ORGANIZATION_ID)
                .verifyComplete();
    }

    @Test
    void organizationIdWhenContextHoldsDefaultOrganizationReturnsEmpty() {
        StepVerifier.create(resolver.organizationId().contextWrite(JOB_FROM_INSTALL_WITHOUT_COMET))
                .verifyComplete();
    }

    static Stream<Named<Context>> contexts() {
        return Stream.of(
                Named.of("no context", Context.empty()),
                Named.of("request", REQUEST),
                Named.of("job carrying an organization", JOB),
                Named.of("job carrying the default organization", JOB_FROM_INSTALL_WITHOUT_COMET));
    }

    @ParameterizedTest
    @MethodSource("contexts")
    void requireOrganizationAdminThrowsForbiddenException(Context context) {
        StepVerifier.create(resolver.requireOrganizationAdmin().contextWrite(context))
                .expectErrorSatisfies(error -> {
                    assertThat(error)
                            .isInstanceOf(ForbiddenException.class)
                            .hasMessage(RemoteOrganizationResolver.UNKNOWN_ORGANIZATION);
                    assertThat(((ForbiddenException) error).getResponse().getStatus()).isEqualTo(403);
                })
                .verify();
    }
}
