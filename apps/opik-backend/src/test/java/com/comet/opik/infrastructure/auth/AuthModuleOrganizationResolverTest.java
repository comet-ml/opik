package com.comet.opik.infrastructure.auth;

import com.comet.opik.infrastructure.AuthenticationConfig;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import static org.assertj.core.api.Assertions.assertThat;

class AuthModuleOrganizationResolverTest {

    private static final String PINNED_DEFAULT_ORGANIZATION_ID = "01a1a1c9-b569-7b5b-9c5c-1aa254d42e50";

    private final AuthModule authModule = new AuthModule();

    @Test
    void organizationResolverWhenAuthenticationDisabledUsesDefaultOrganization() {
        var config = new AuthenticationConfig();
        config.setEnabled(false);
        config.setReactService(null);

        var resolver = authModule.organizationResolver(config);

        assertThat(resolver).isInstanceOf(DefaultOrganizationResolver.class);
        StepVerifier.create(resolver.organizationId())
                .expectNext(PINNED_DEFAULT_ORGANIZATION_ID)
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
