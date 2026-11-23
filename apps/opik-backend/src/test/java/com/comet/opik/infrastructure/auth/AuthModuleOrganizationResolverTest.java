package com.comet.opik.infrastructure.auth;

import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.utils.AsyncUtils;
import io.dropwizard.util.Duration;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.redisson.api.RedissonReactiveClient;
import reactor.test.StepVerifier;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AuthModuleOrganizationResolverTest {

    private static final String PINNED_DEFAULT_ORGANIZATION_ID = "01a1a1c9-b569-7b5b-9c5c-1aa254d42e50";

    private final AuthModule authModule = new AuthModule();
    private final RetriableHttpClient retriableHttpClient = mock(RetriableHttpClient.class);
    private final RedissonReactiveClient redissonClient = mock(RedissonReactiveClient.class);

    static Stream<Arguments> authentication() {
        return Stream.of(
                arguments("authentication off", AuthenticationConfig.builder().enabled(false).build(),
                        DefaultOrganizationResolver.class, List.of(PINNED_DEFAULT_ORGANIZATION_ID)),
                arguments("authentication on", enabledAuthentication(), RemoteOrganizationResolver.class,
                        List.of()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("authentication")
    void organizationResolverFollowsAuthentication(String name, AuthenticationConfig config,
            Class<? extends OrganizationResolver> expectedType, List<String> expectedOrganizationIds) {
        var resolver = authModule.organizationResolver(config, toggles(true), RequestContext::new,
                retriableHttpClient, redissonClient);

        assertThat(resolver).isExactlyInstanceOf(expectedType);
        StepVerifier.create(resolver.organizationId())
                .expectNextSequence(expectedOrganizationIds)
                .verifyComplete();
        verifyNoInteractions(retriableHttpClient, redissonClient);
    }

    @Test
    void organizationResolverWhenModelPricesAreOffMakesNoCometOrRedisCall() {
        var request = RequestContext.builder()
                .userName(RandomStringUtils.secure().nextAlphanumeric(10))
                .workspaceId(UUID.randomUUID().toString())
                .workspaceName(RandomStringUtils.secure().nextAlphanumeric(10))
                .cometCredentials(CometCredentials.apiKey(UUID.randomUUID().toString()))
                .build();

        var resolver = authModule.organizationResolver(enabledAuthentication(), toggles(false), () -> request,
                retriableHttpClient, redissonClient);

        StepVerifier.create(resolver.organizationId()
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, request)))
                .verifyComplete();
        verifyNoInteractions(retriableHttpClient, redissonClient);
    }

    private static AuthenticationConfig enabledAuthentication() {
        return AuthenticationConfig.builder()
                .enabled(true)
                .reactService(new AuthenticationConfig.UrlConfig(
                        "http://%s.example.com".formatted(RandomStringUtils.secure().nextAlphabetic(10))))
                .requestTimeout(Duration.seconds(3))
                .requestRetryMinBackoff(Duration.milliseconds(250))
                .requestRetryMaxBackoff(Duration.seconds(1))
                .organizationCacheTTL(Duration.minutes(10))
                .organizationCacheStaleTTL(Duration.minutes(60))
                .organizationCacheRetryAfterFailure(Duration.seconds(30))
                .build();
    }

    private static ServiceTogglesConfig toggles(boolean modelPricesEnabled) {
        var toggles = new ServiceTogglesConfig();
        toggles.setModelPricesEnabled(modelPricesEnabled);
        return toggles;
    }
}
