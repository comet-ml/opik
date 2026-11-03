package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.AuthenticationConfig;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class AuthModuleOrganizationResolverTest {

    private final AuthModule authModule = new AuthModule();

    @Test
    void organizationResolverWhenAuthenticationDisabledUsesDefaultOrganization() {
        var config = new AuthenticationConfig();
        config.setEnabled(false);
        config.setReactService(null);

        var resolver = authModule.organizationResolver(config);

        assertThat(resolver).isInstanceOf(DefaultOrganizationResolver.class);
        StepVerifier.create(resolver.organizationId())
                .expectNext(ProjectService.DEFAULT_ORGANIZATION_ID)
                .verifyComplete();
    }

    @Test
    void organizationResolverWhenAuthenticationEnabledUsesRemote() {
        var config = new AuthenticationConfig();
        config.setEnabled(true);
        config.setReactService(new AuthenticationConfig.UrlConfig("http://comet.example.com"));

        var resolver = authModule.organizationResolver(config);

        assertThat(resolver).isInstanceOf(RemoteOrganizationResolver.class);
        StepVerifier.create(resolver.organizationId())
                .verifyComplete();
    }
}
