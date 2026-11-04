package com.comet.opik.infrastructure.auth;

import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.utils.AsyncUtils;
import io.dropwizard.util.Duration;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonReactiveClient;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AuthModuleOrganizationResolverTest {

    private static final String PINNED_DEFAULT_ORGANIZATION_ID = "01a1a1c9-b569-7b5b-9c5c-1aa254d42e50";

    private final AuthModule authModule = new AuthModule();
    private final RetriableHttpClient retriableHttpClient = mock(RetriableHttpClient.class);
    private final RedissonReactiveClient redissonClient = mock(RedissonReactiveClient.class);

    @Test
    void organizationResolverWhenAuthenticationDisabledUsesDefaultOrganization() {
        var config = new AuthenticationConfig();
        config.setEnabled(false);
        config.setReactService(null);

        var resolver = authModule.organizationResolver(config, toggles(true), RequestContext::new,
                retriableHttpClient, redissonClient);

        assertThat(resolver).isInstanceOf(DefaultOrganizationResolver.class);
        StepVerifier.create(resolver.organizationId())
                .expectNext(PINNED_DEFAULT_ORGANIZATION_ID)
                .verifyComplete();
        verifyNoInteractions(retriableHttpClient, redissonClient);
    }

    @Test
    void organizationResolverWhenModelPricesAreOffMakesNoCometOrRedisCall() {
        var credentials = CometCredentials.apiKey(UUID.randomUUID().toString());
        var request = RequestContext.builder()
                .userName("user")
                .workspaceId(UUID.randomUUID().toString())
                .workspaceName("workspace")
                .cometCredentials(credentials)
                .organizationLookup(new RequestOrganizationLookup(credentials))
                .build();

        var resolver = authModule.organizationResolver(enabledAuthentication(), toggles(false), () -> request,
                retriableHttpClient, redissonClient);

        StepVerifier.create(resolver.organizationId()
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, request)))
                .verifyComplete();
        verifyNoInteractions(retriableHttpClient, redissonClient);
    }

    @Test
    void organizationResolverWhenAuthenticationEnabledUsesRemote() {
        var config = enabledAuthentication();

        var resolver = authModule.organizationResolver(config, toggles(true), RequestContext::new,
                retriableHttpClient, redissonClient);

        assertThat(resolver).isInstanceOf(RemoteOrganizationResolver.class);
        StepVerifier.create(resolver.organizationId())
                .verifyComplete();
        verifyNoInteractions(retriableHttpClient, redissonClient);
    }

    private static AuthenticationConfig enabledAuthentication() {
        var config = new AuthenticationConfig();
        config.setEnabled(true);
        config.setReactService(new AuthenticationConfig.UrlConfig("http://comet.example.com"));
        config.setRequestTimeout(Duration.seconds(3));
        config.setRequestRetryMinBackoff(Duration.milliseconds(250));
        config.setRequestRetryMaxBackoff(Duration.seconds(1));
        return config;
    }

    private static ServiceTogglesConfig toggles(boolean modelPricesEnabled) {
        var toggles = new ServiceTogglesConfig();
        toggles.setModelPricesEnabled(modelPricesEnabled);
        return toggles;
    }
}
